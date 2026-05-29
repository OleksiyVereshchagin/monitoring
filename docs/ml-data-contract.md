# ML Data Contract

## Purpose

This document fixes the data rules for the energy monitoring time-series dataset before the LSTM module is implemented.

The telemetry module is not just CRUD. It is the source of training, validation, testing, forecasting, and anomaly-detection data.

## 1. Data Quality Contract

### Time-Series Rule

- One device is treated as one continuous time series.
- The expected sampling step is exactly 10 minutes.
- For each active device, the target sequence is:

```text
t0, t0 + 10 min, t0 + 20 min, ...
```

### Timestamp Rules

- `Reading.timestamp` must represent the measurement time, not the server receive time.
- If a sensor cannot provide timestamp, the backend may use server time only for manual/API fallback data.
- Generator-created readings must always use aligned timestamps:

```text
minute % 10 == 0
seconds == 0
nanos == 0
```

### Missing Values

- For ML training data, missing timestamps are not allowed inside a training window.
- A 24-hour window contains exactly 144 readings per device:

```text
24 hours * 6 readings per hour = 144 readings
```

- A 60-minute window contains exactly 6 readings per device.
- If a timestamp is missing, the window is excluded from training until an imputation strategy is implemented.

### Ordering

- Readings must be ordered by `timestamp ASC` before building ML windows.
- API pagination may return descending data for UI convenience, but ML dataset preparation must always sort ascending.

### Duplicate Readings

- The target logical uniqueness rule is:

```text
device_id + timestamp + source
```

- If duplicates exist, ML preparation must keep only one value per timestamp before training.
- Preferred duplicate policy for future implementation: keep the latest inserted reading.

### Active Devices

- Only devices with `active = true` are used for generator output and ML training by default.
- Inactive devices can remain in the database for historical analysis, but they are excluded from new synthetic data generation.

## 2. Normalization Strategy

### Default Strategy

Use per-device min-max normalization for LSTM training:

```text
normalized = (value - device_min) / (device_max - device_min)
```

This is the default because different device types have very different power ranges. A fridge, light, kettle, and EV charger should not share one global scale.

### Scaling Scope

- Fit normalization parameters only on the training period.
- Reuse the same parameters for validation, testing, and inference.
- Do not fit min/max on validation or test data.

### Stored Parameters

For every trained model version, persist:

- `deviceId`
- `minPowerConsumption`
- `maxPowerConsumption`
- training period start/end
- model version or model file name

### Zero-Range Case

If `device_max == device_min`, use fallback:

```text
normalized = 0.0
```

This prevents division by zero for constant devices.

### Optional Electrical Features

`voltage` and `current` are optional context features.

Initial LSTM input can start with:

```text
powerConsumption
```

Later expanded feature vector:

```text
powerConsumption, voltage, current, nominalPower, behaviorProfile
```

## 3. Training Boundary

### Dataset Split

Use chronological splitting, never random splitting:

```text
first 70%  -> training
next 15%   -> validation
last 15%   -> testing
```

Reason: this is time-series forecasting. Random split leaks future behavior into training and makes results look better than they are.

### Windowing

For the diploma baseline model:

```text
X = previous 24 hours
y = next timestamp
```

With 10-minute readings:

```text
X length = 144 points
y length = 1 point
```

For later 24-hour forecast:

```text
X = previous 24 hours
y = next 24 hours
```

With 10-minute readings:

```text
X length = 144 points
y length = 144 points
```

### Validation Use

Validation data is used for:

- selecting epochs
- checking overfitting
- comparing hyperparameters

Validation data must not be used for final quality reporting.

### Test Use

Test data is used once for final metrics:

- MSE
- MAE
- optionally MAPE, if values are not close to zero

## 4. Generator Contract

The future `DataGeneratorService` must follow these rules:

- generate readings every 10 minutes;
- align timestamps to 10-minute boundaries;
- generate data for active devices only;
- generate continuous data without missing timestamps;
- assign `source = "GENERATOR"`;
- assign one `sessionId` per generator run or historical backfill run.

Suggested session format:

```text
generator-YYYYMMDD-HHmm
backfill-YYYYMMDD-HHmm
```

## 5. Diploma Explanation

The system stores raw telemetry, but ML uses a prepared time-series dataset.

The preparation pipeline is:

```text
raw readings
-> filter active devices
-> sort by timestamp ASC
-> validate 10-minute continuity
-> remove/resolve duplicates
-> build windows
-> fit per-device min-max scaler on training data
-> train LSTM
-> validate
-> test
```

This closes the main methodological risks:

- the time step is fixed;
- missing values are explicitly handled;
- normalization is defined;
- train/validation/test boundaries are chronological and reproducible.
