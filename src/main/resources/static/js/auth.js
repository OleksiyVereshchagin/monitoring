const TOKEN_KEY = "energy-monitor-token";
const USERNAME_KEY = "energy-monitor-username";
const TOKEN_MAX_AGE_SECONDS = 60 * 60 * 24;

let consumptionChart = null;
let lastForecastTotal = null;
let lastAnomalies = [];
let lastModelStatus = null;
let lastCurrentPower = null;
let forecastRetryCount = 0;
let forecastRetryTimer = null;

const getAuthState = () => ({
    token: localStorage.getItem(TOKEN_KEY),
    username: localStorage.getItem(USERNAME_KEY)
});

const setAuthCookie = (token) => {
    document.cookie = `${TOKEN_KEY}=${encodeURIComponent(token)}; Path=/; Max-Age=${TOKEN_MAX_AGE_SECONDS}; SameSite=Lax`;
};

const clearAuthCookie = () => {
    document.cookie = `${TOKEN_KEY}=; Path=/; Max-Age=0; SameSite=Lax`;
};

const apiFetch = async (url, options = {}) => {
    const { token } = getAuthState();
    const headers = {
        Accept: "application/json",
        ...(options.body ? { "Content-Type": "application/json" } : {}),
        ...(token ? { Authorization: `Bearer ${token}` } : {}),
        ...(options.headers || {})
    };

    let response;
    try {
        response = await fetch(url, { ...options, headers });
    } catch (error) {
        throw new Error("Сервер недоступний. Перевірте, що Spring Boot запущений на тому самому порту, що й сторінка.");
    }

    const redirectedToLogin = response.redirected && new URL(response.url).pathname === "/login";
    if (response.status === 401 || redirectedToLogin) {
        localStorage.removeItem(TOKEN_KEY);
        localStorage.removeItem(USERNAME_KEY);
        clearAuthCookie();
        window.location.href = "/login";
        throw new Error("Потрібно увійти в систему.");
    }

    return response;
};

const isJsonResponse = (response) => {
    const contentType = response.headers.get("content-type") || "";
    return contentType.includes("application/json");
};

const readJsonResponse = async (response, fallback) => {
    if (!isJsonResponse(response)) {
        throw new Error(fallback);
    }

    return response.json();
};

const readApiError = async (response, fallback) => {
    try {
        if (!isJsonResponse(response)) {
            return fallback;
        }
        const data = await response.json();
        return data.message || data.error || fallback;
    } catch (error) {
        return fallback;
    }
};

const setMessage = (element, text, success = false) => {
    if (!element) {
        return;
    }

    element.textContent = text;
    element.classList.toggle("success", success);
};

const setText = (selector, text) => {
    const element = document.querySelector(selector);
    if (element) {
        element.textContent = text;
    }
};

const formatNumber = (value, digits = 2) => {
    if (value === null || value === undefined || Number.isNaN(Number(value))) {
        return "--";
    }

    return Number(value).toFixed(digits);
};

const formatDateTime = (value) => {
    if (!value) {
        return "--";
    }

    return new Date(value).toLocaleString("uk-UA");
};

const formatEnum = (value) => {
    if (!value) {
        return "-";
    }

    const labels = {
        APARTMENT: "Квартира",
        HOUSE: "Будинок",
        OFFICE: "Офіс",
        GARAGE: "Гараж",
        COTTAGE: "Дача",
        OTHER: "Інше",
        FRIDGE: "Холодильник",
        TV: "Телевізор",
        AC: "Кондиціонер",
        LIGHT: "Освітлення",
        HEATER: "Обігрівач",
        BOILER: "Бойлер",
        WASHING_MACHINE: "Пральна машина",
        OVEN: "Духовка",
        ROUTER: "Роутер",
        EV_CHARGER: "Зарядка авто",
        CONSTANT: "Постійна",
        CYCLIC: "Циклічна",
        INTERMITTENT: "Переривчаста",
        PEAK_BASED: "Пікова"
    };

    return labels[value] || value.toLowerCase().replaceAll("_", " ");
};

const describeCurrentLoad = (value) => {
    if (!Number.isFinite(value)) {
        return "Немає актуального зрізу.";
    }

    if (value < 1) {
        return "Низьке навантаження.";
    }
    if (value < 3) {
        return "Помірне навантаження для квартири.";
    }
    if (value < 6) {
        return "Підвищене навантаження, перевірте потужні пристрої.";
    }
    return "Високе навантаження, можливий одночасний запуск кількох потужних пристроїв.";
};

const formatChartLabel = (value) => {
    const date = new Date(value);
    return `${String(date.getHours()).padStart(2, "0")}:${String(date.getMinutes()).padStart(2, "0")}`;
};

const scheduleForecastRetry = () => {
    if (!document.querySelector("[data-metrics]") || forecastRetryTimer || forecastRetryCount >= 6) {
        return;
    }

    forecastRetryCount += 1;
    forecastRetryTimer = window.setTimeout(async () => {
        forecastRetryTimer = null;
        await loadDashboardMetrics();
    }, 5000);
};

const clearForecastRetry = () => {
    forecastRetryCount = 0;
    if (forecastRetryTimer) {
        window.clearTimeout(forecastRetryTimer);
        forecastRetryTimer = null;
    }
};

const getInitial = (username) => {
    if (!username) {
        return "U";
    }

    return username.trim().charAt(0).toUpperCase();
};

const updateAuthNavigation = () => {
    const { token, username } = getAuthState();
    const isAuthenticated = Boolean(token);

    document.querySelectorAll("[data-guest-nav]").forEach((element) => {
        element.hidden = isAuthenticated;
    });

    document.querySelectorAll("[data-user-nav], [data-auth-required]").forEach((element) => {
        element.hidden = !isAuthenticated;
    });

    document.querySelectorAll("[data-profile-name]").forEach((element) => {
        element.textContent = username || "Користувач";
    });

    document.querySelectorAll("[data-profile-avatar]").forEach((element) => {
        element.textContent = getInitial(username);
    });
};

const redirectAuthorizedGuest = () => {
    const { token } = getAuthState();

    if (document.body.dataset.authPage === "guest-only" && token) {
        window.location.href = "/dashboard";
    }
};

const redirectUnauthorizedUser = () => {
    const { token } = getAuthState();
    const protectedPaths = ["/dashboard", "/profile", "/anomalies"];
    const currentPath = window.location.pathname;

    if (!token && protectedPaths.some(path => currentPath.startsWith(path))) {
        window.location.href = "/login";
    }
};

const bindLogout = () => {
    document.querySelectorAll("[data-logout]").forEach((button) => {
        button.addEventListener("click", () => {
            localStorage.removeItem(TOKEN_KEY);
            localStorage.removeItem(USERNAME_KEY);
            clearAuthCookie();
            updateAuthNavigation();
            window.location.href = "/login";
        });
    });
};

const bindAuthForms = () => {
    document.querySelectorAll("[data-auth-form]").forEach((form) => {
        form.addEventListener("submit", async (event) => {
            event.preventDefault();

            const mode = form.dataset.authForm;
            const message = form.querySelector("[data-form-message]");
            const payload = Object.fromEntries(new FormData(form).entries());

            setMessage(message, "");

            try {
                const response = await fetch(`/api/auth/${mode}`, {
                    method: "POST",
                    headers: {
                        "Content-Type": "application/json"
                    },
                    body: JSON.stringify(payload)
                });

                if (!response.ok) {
                    throw new Error(await readApiError(response, "Перевірте введені дані та спробуйте ще раз."));
                }

                const data = await response.json();
                localStorage.setItem(TOKEN_KEY, data.token);
                localStorage.setItem(USERNAME_KEY, data.username);
                setAuthCookie(data.token);

                updateAuthNavigation();
                setMessage(message, "Успішно. Переходимо до dashboard...", true);

                window.setTimeout(() => {
                    window.location.href = "/dashboard";
                }, 500);
            } catch (error) {
                setMessage(message, error.message);
            }
        });
    });
};

const updateRecommendations = () => {
    const list = document.querySelector("[data-recommendations]");
    if (!list) {
        return;
    }

    const recommendations = [];
    const hasModel = lastModelStatus?.modelReady;
    const spike = lastAnomalies.find(a => a.type === "SPIKE");
    const drop = lastAnomalies.find(a => a.type === "DROP");

    if (!hasModel) {
        recommendations.push("Модель ще не готова: прогноз з'явиться після підготовки або навчання моделі.");
    } else if (lastForecastTotal !== null) {
        if (lastForecastTotal >= 20) {
            recommendations.push("Очікується підвищене споживання за наступні 24 години. Перевірте потужні пристрої та перенесіть частину навантаження на інший час.");
        } else if (lastForecastTotal >= 8) {
            recommendations.push("Прогноз споживання помірний. Система працює стабільно, але варто стежити за піковими пристроями.");
        } else {
            recommendations.push("Прогноз споживання низький. Поточний режим енергоспоживання виглядає економним.");
        }
    }

    if (spike) {
        recommendations.push(`Виявлено різке перевищення для пристрою "${spike.deviceName}". Перевірте, чи це очікуване навантаження.`);
    }

    if (drop) {
        recommendations.push(`Виявлено різке падіння споживання для пристрою "${drop.deviceName}". Переконайтесь, що пристрій працює коректно.`);
    }

    if (!recommendations.length) {
        recommendations.push("Дані завантажені. Критичних відхилень зараз не виявлено.");
    }

    list.innerHTML = recommendations.map(item => `<li>${item}</li>`).join("");
};

const renderModelStatus = (status) => {
    lastModelStatus = status;

    const pill = document.querySelector("[data-model-status-pill]");
    if (pill) {
        pill.textContent = status.modelReady ? "Модель готова" : "Не навчена";
        pill.classList.toggle("active", status.modelReady);
    }

    setText("[data-model-message]", status.message || "Стан моделі отримано.");
    setText("[data-model-trained-at]", formatDateTime(status.trainedAt));
    setText("[data-model-points]", status.totalPoints ?? status.dataQuality?.totalReadings ?? "--");
};

const loadModelStatus = async () => {
    const modelPanel = document.querySelector("[data-model-status-pill]");
    if (!modelPanel) {
        return null;
    }

    try {
        const response = await apiFetch("/api/ml/model-status");
        if (!response.ok) {
            throw new Error(await readApiError(response, "Не вдалося отримати стан моделі."));
        }

        const status = await readJsonResponse(response, "Сервер повернув некоректний стан моделі.");
        renderModelStatus(status);
        updateRecommendations();
        return status;
    } catch (error) {
        renderModelStatus({
            modelReady: false,
            message: error.message,
            dataQuality: null
        });
        updateRecommendations();
        return null;
    }
};

const loadDashboardMetrics = async () => {
    if (!document.querySelector("[data-metrics]")) {
        return;
    }

    try {
        const readingsResponse = await apiFetch("/api/ml/current-power");
        if (readingsResponse.ok) {
            const data = await readJsonResponse(readingsResponse, "Сервер повернув некоректну відповідь для поточного споживання.");
            lastCurrentPower = Number(data.value);
            setText("[data-current-power]", `${data.value} кВт`);
            setText("[data-current-load-status]", describeCurrentLoad(lastCurrentPower));
        }
    } catch (error) {
        lastCurrentPower = null;
        setText("[data-current-power]", "-- кВт");
        setText("[data-current-load-status]", "Не вдалося отримати поточний зріз.");
        console.error("Current power error:", error);
    }

    const status = await loadModelStatus();
    const forecastEl = document.querySelector("[data-forecast-power]");
    if (!status?.modelReady) {
        lastForecastTotal = null;
        if (forecastEl) {
            forecastEl.textContent = "Готуємо прогноз";
            forecastEl.title = status?.message || "Модель ще не навчена.";
        }
    }

    try {
        const forecastResponse = await apiFetch("/api/ml/forecast");
        if (!forecastResponse.ok) {
            throw new Error(await readApiError(forecastResponse, "Не вдалося побудувати прогноз."));
        }

        const forecast = await readJsonResponse(forecastResponse, "Сервер повернув некоректну відповідь для прогнозу.");
        lastForecastTotal = forecast.reduce((a, b) => a + b, 0) * (10 / 60);
        if (forecastEl) {
            forecastEl.textContent = `${formatNumber(lastForecastTotal, 2)} кВт·год`;
            forecastEl.removeAttribute("title");
        }
        clearForecastRetry();
    } catch (error) {
        lastForecastTotal = Number.isFinite(lastCurrentPower) && lastCurrentPower > 0
            ? lastCurrentPower * 24
            : null;
        if (forecastEl) {
            forecastEl.textContent = lastForecastTotal !== null
                ? `${formatNumber(lastForecastTotal, 2)} кВт·год`
                : "Готуємо прогноз";
            forecastEl.title = error.message;
        }
        scheduleForecastRetry();
    }

    updateRecommendations();
};

const renderHouseholds = (households) => {
    const list = document.querySelector("[data-household-list]");
    const select = document.querySelector("[data-household-select]");
    const count = document.querySelector("[data-household-count]");

    if (count) {
        count.textContent = households.length;
    }

    if (select) {
        select.innerHTML = households.length
            ? households.map((household) => `<option value="${household.id}">${household.name}</option>`).join("")
            : `<option value="">Спочатку створіть групу</option>`;
    }

    if (!list) {
        return;
    }

    if (!households.length) {
        list.innerHTML = `<p class="muted">Груп ще немає. Створіть першу групу, щоб додавати пристрої.</p>`;
        return;
    }

    list.innerHTML = households.map((household) => `
        <article class="entity-item">
            <div>
                <strong>${household.name}</strong>
                <span>${formatEnum(household.type)}</span>
            </div>
        </article>
    `).join("");
};

const renderDevices = (devices) => {
    const list = document.querySelector("[data-device-list]");
    const count = document.querySelector("[data-device-count]");

    if (count) {
        count.textContent = devices.filter((device) => device.active).length;
    }

    if (!list) {
        return;
    }

    if (!devices.length) {
        list.innerHTML = `<p class="muted">Пристроїв ще немає. Після додавання система автоматично підготує історичні показники.</p>`;
        return;
    }

    list.innerHTML = devices.map((device) => `
        <article class="entity-item">
            <div>
                <strong>${device.name}</strong>
                <span>${formatEnum(device.type)} · ${formatEnum(device.behaviorProfile)}</span>
                <small>${device.householdName || "Без групи"}${device.nominalPower ? ` · ${device.nominalPower} кВт` : ""}</small>
            </div>
            <span class="status-pill ${device.active ? "active" : ""}">${device.active ? "active" : "inactive"}</span>
        </article>
    `).join("");
};

const loadCoreStructure = async () => {
    if (!document.querySelector("[data-core-manager]")) {
        return;
    }

    const [householdsResponse, devicesResponse] = await Promise.all([
        apiFetch("/api/households"),
        apiFetch("/api/devices")
    ]);

    if (!householdsResponse.ok || !devicesResponse.ok) {
        throw new Error("Не вдалося завантажити структуру енергосистеми.");
    }

    const households = await readJsonResponse(householdsResponse, "Сервер повернув некоректний список груп.");
    const devices = await readJsonResponse(devicesResponse, "Сервер повернув некоректний список пристроїв.");

    renderHouseholds(households);
    renderDevices(devices);
};

const bindCoreForms = () => {
    const householdForm = document.querySelector("[data-household-form]");
    const householdMessage = document.querySelector("[data-household-message]");
    const deviceForm = document.querySelector("[data-device-form]");
    const deviceMessage = document.querySelector("[data-device-message]");

    householdForm?.addEventListener("submit", async (event) => {
        event.preventDefault();
        setMessage(householdMessage, "");

        const payload = Object.fromEntries(new FormData(householdForm).entries());

        try {
            const response = await apiFetch("/api/households", {
                method: "POST",
                body: JSON.stringify(payload)
            });

            if (!response.ok) {
                throw new Error(await readApiError(response, "Не вдалося створити групу."));
            }

            householdForm.reset();
            setMessage(householdMessage, "Групу створено.", true);
            await loadCoreStructure();
        } catch (error) {
            setMessage(householdMessage, error.message);
        }
    });

    deviceForm?.addEventListener("submit", async (event) => {
        event.preventDefault();
        setMessage(deviceMessage, "");

        const formData = new FormData(deviceForm);
        const payload = Object.fromEntries(formData.entries());

        if (payload.nominalPower) {
            payload.nominalPower = Number(payload.nominalPower);
        } else {
            delete payload.nominalPower;
        }

        if (payload.householdId) {
            payload.householdId = Number(payload.householdId);
        }

        if (!payload.behaviorProfile) {
            delete payload.behaviorProfile;
        }

        payload.active = true;

        try {
            const response = await apiFetch("/api/devices", {
                method: "POST",
                body: JSON.stringify(payload)
            });

            if (!response.ok) {
                throw new Error(await readApiError(response, "Не вдалося додати пристрій."));
            }

            deviceForm.reset();
            setMessage(deviceMessage, "Пристрій додано. Історичні дані готуються автоматично.", true);
            await loadCoreStructure();
            await loadDashboardMetrics();
            await initConsumptionChart();
        } catch (error) {
            setMessage(deviceMessage, error.message);
        }
    });
};

const bindMLControls = () => {
    const trainBtn = document.querySelector("[data-train-model]");
    const demoModelBtn = document.querySelector("[data-use-demo-model]");
    const simulateBtn = document.querySelector("[data-simulate-anomaly]");
    const message = document.querySelector("[data-ml-message]");

    demoModelBtn?.addEventListener("click", async () => {
        setMessage(message, "Підключаємо готову демо-модель...");
        demoModelBtn.disabled = true;

        try {
            const response = await apiFetch("/api/ml/use-demo-model", { method: "POST" });
            if (!response.ok) {
                throw new Error(await readApiError(response, "Не вдалося підключити готову демо-модель."));
            }

            const status = await readJsonResponse(response, "Сервер повернув некоректний стан демо-моделі.");
            renderModelStatus(status);
            setMessage(message, "Готову демо-модель підключено. Прогноз можна переглянути на dashboard.", true);
            await loadDashboardMetrics();
        } catch (error) {
            setMessage(message, error.message);
        } finally {
            demoModelBtn.disabled = false;
        }
    });

    trainBtn?.addEventListener("click", async () => {
        const confirmed = confirm("Повторне навчання може тривати 20-60 хвилин. Для демо краще використовувати готову модель. Запустити навчання?");
        if (!confirmed) {
            return;
        }

        setMessage(message, "Навчання запущено. Не оновлюйте сторінку, доки сервер не поверне результат.");
        trainBtn.disabled = true;

        try {
            const response = await apiFetch("/api/ml/train", { method: "POST" });
            if (!response.ok) {
                throw new Error(await readApiError(response, "Не вдалося навчити модель."));
            }

            const result = await readJsonResponse(response, "Сервер повернув некоректний результат навчання.");
            setMessage(
                message,
                `Модель навчена. Підготовлено ${result.totalSamples} навчальних вікон.`,
                true
            );
            await loadDashboardMetrics();
        } catch (error) {
            setMessage(message, error.message);
        } finally {
            trainBtn.disabled = false;
        }
    });

    simulateBtn?.addEventListener("click", async () => {
        try {
            const devicesResponse = await apiFetch("/api/devices");
            const deviceList = await readJsonResponse(devicesResponse, "Сервер повернув некоректний список пристроїв.");
            if (!deviceList.length) {
                setMessage(message, "Спочатку додайте хоча б один пристрій.");
                return;
            }

            const response = await apiFetch("/api/ml/simulate-anomaly", { method: "POST" });

            if (!response.ok) {
                throw new Error(await readApiError(response, "Не вдалося симулювати аномалію."));
            }

            setMessage(message, "Аномалію додано в журнал.", true);
            await loadAnomalies();
        } catch (error) {
            setMessage(message, error.message);
        }
    });
};

const loadAnomalies = async () => {
    const tbody = document.querySelector("[data-anomaly-list]");
    if (!tbody) {
        return;
    }

    const limit = tbody.dataset.anomalyLimit;
    const anomalyUrl = limit && limit !== "all"
        ? `/api/ml/anomalies?limit=${encodeURIComponent(limit)}`
        : "/api/ml/anomalies";
    const columnCount = tbody.closest("table")?.querySelectorAll("thead th").length || 6;

    try {
        const response = await apiFetch(anomalyUrl);
        if (!response.ok) {
            throw new Error(await readApiError(response, "Не вдалося завантажити журнал аномалій."));
        }

        const anomalies = await readJsonResponse(response, "Сервер повернув некоректний журнал аномалій.");
        lastAnomalies = anomalies;

        if (!anomalies.length) {
            tbody.innerHTML = `<tr><td colspan="${columnCount}" class="empty-table">Аномалій не виявлено.</td></tr>`;
            setText("[data-anomaly-status]", "Аномалій поки немає. Після симуляції або ML-detection вони залишаться в журналі.");
            updateRecommendations();
            return;
        }

        const visibleCount = limit && limit !== "all" ? Number(limit) : anomalies.length;
        setText(
            "[data-anomaly-status]",
            limit && limit !== "all"
                ? `Показуємо ${Math.min(anomalies.length, visibleCount)} останніх аномалій. Повна історія доступна на окремій сторінці.`
                : `Усього в журналі: ${anomalies.length}. SPIKE - різке перевищення, DROP - різке падіння.`
        );

        tbody.innerHTML = anomalies.map(a => `
            <tr>
                <td>${formatDateTime(a.timestamp)}</td>
                <td>${a.deviceName}</td>
                <td>${a.actualValue} кВт</td>
                <td>${a.expectedValue} кВт</td>
                <td>${a.deviationPercent}%</td>
                <td>
                    <span class="status-pill ${a.type === "SPIKE" ? "active" : ""}">
                        ${a.type === "SPIKE" ? "SPIKE: різке перевищення" : "DROP: різке падіння"}
                    </span>
                </td>
            </tr>
        `).join("");
    } catch (error) {
        console.error("Anomalies error:", error);
    }

    updateRecommendations();
};

const initConsumptionChart = async () => {
    const canvas = document.getElementById("consumptionChart");

    if (!canvas || typeof Chart === "undefined") {
        return;
    }

    if (consumptionChart) {
        consumptionChart.destroy();
        consumptionChart = null;
    }

    let labels = [];
    let data = [];

    try {
        const response = await apiFetch("/api/ml/readings/hourly");
        if (response.ok) {
            const json = await readJsonResponse(response, "Сервер повернув некоректні дані для графіка.");
            if (json.labels?.length) {
                labels = json.labels.map(formatChartLabel);
                data = json.values.map(value => value === null || value === undefined ? null : Number(value));
            }
            setText("[data-chart-status]", "Readings за сьогодні");
        }
    } catch (error) {
        setText("[data-chart-status]", "Графік тимчасово недоступний");
        console.error("Chart error:", error);
    }

    consumptionChart = new Chart(canvas, {
        type: "line",
        data: {
            labels,
            datasets: [
                {
                    label: "Споживання (кВт)",
                    data,
                    borderColor: "#0f766e",
                    backgroundColor: "rgba(15, 118, 110, 0.12)",
                    fill: true,
                    spanGaps: false,
                    cubicInterpolationMode: "monotone",
                    tension: 0.48,
                    pointRadius: 2
                }
            ]
        },
        options: {
            responsive: true,
            maintainAspectRatio: false,
            plugins: {
                legend: { display: false }
            },
            scales: {
                x: {
                    ticks: {
                        maxTicksLimit: 13
                    }
                },
                y: { beginAtZero: true }
            }
        }
    });
};

const startAutoRefresh = () => {
    if (!document.querySelector("[data-metrics]")) {
        return;
    }

    setInterval(async () => {
        try {
            await loadDashboardMetrics();
            await loadAnomalies();
            await initConsumptionChart();
        } catch (error) {
            console.error("Auto refresh error:", error);
        }
    }, 10 * 60 * 1000);
};

document.addEventListener("DOMContentLoaded", async () => {
    updateAuthNavigation();
    redirectAuthorizedGuest();
    redirectUnauthorizedUser();
    bindLogout();
    bindAuthForms();
    bindCoreForms();
    bindMLControls();

    try {
        await initConsumptionChart();
        await loadCoreStructure();
        await loadDashboardMetrics();
        await loadAnomalies();
        startAutoRefresh();
    } catch (error) {
        console.error(error);
    }
});
