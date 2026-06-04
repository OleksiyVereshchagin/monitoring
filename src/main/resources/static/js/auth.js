const TOKEN_KEY = "energy-monitor-token";
const USERNAME_KEY = "energy-monitor-username";
const TOKEN_MAX_AGE_SECONDS = 60 * 60 * 24;

localStorage.removeItem(TOKEN_KEY);
localStorage.removeItem(USERNAME_KEY);

const getAuthState = () => ({
    token: sessionStorage.getItem(TOKEN_KEY),
    username: sessionStorage.getItem(USERNAME_KEY)
});

const setAuthCookie = (token) => {
    document.cookie = `${TOKEN_KEY}=; Path=/; Max-Age=0; SameSite=Lax`;
};

const clearAuthCookie = () => {
    document.cookie = `${TOKEN_KEY}=; Path=/; Max-Age=0; SameSite=Lax`;
};

clearAuthCookie();

const apiFetch = async (url, options = {}) => {
    const { token } = getAuthState();
    const headers = {
        ...(options.body ? { "Content-Type": "application/json" } : {}),
        ...(token ? { Authorization: `Bearer ${token}` } : {}),
        ...(options.headers || {})
    };

    const response = await fetch(url, { ...options, headers });
    if (response.status === 401) {
        sessionStorage.removeItem(TOKEN_KEY);
        sessionStorage.removeItem(USERNAME_KEY);
        clearAuthCookie();
        window.location.href = "/login";
        throw new Error("Потрібно увійти в систему.");
    }

    return response;
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

const loadDashboardMetrics = async () => {
    if (!document.querySelector("[data-metrics]")) {
        return;
    }

    try {
        const readingsResponse = await apiFetch("/api/ml/current-power");
        if (readingsResponse.ok) {
            const data = await readingsResponse.json();
            const current = document.querySelector("[data-current-power]");
            if (current) {
                current.textContent = `${data.value} кВт`;
            }
        }
    } catch (error) {
        console.error("Current power error:", error);
    }

    try {
        const forecastResponse = await apiFetch("/api/ml/forecast");
        if (forecastResponse.ok) {
            const forecast = await forecastResponse.json();
            const total = (forecast.reduce((a, b) => a + b, 0) * (10 / 60)).toFixed(2);
            const forecastEl = document.querySelector("[data-forecast-power]");
            if (forecastEl) {
                forecastEl.textContent = `${total} кВт·год`;
            }
        }
    } catch (error) {
        const forecastEl = document.querySelector("[data-forecast-power]");
        if (forecastEl) {
            forecastEl.textContent = "-- кВт·год";
        }
    }
};

const refreshDashboard = async () => {
    await Promise.allSettled([
        loadCoreStructure(),
        loadAnomalies()
    ]);
    await loadDashboardMetrics().catch(error => console.error("Metrics refresh error:", error));
    await initConsumptionChart().catch(error => console.error("Chart refresh error:", error));
    await loadDeviceContribution().catch(error => console.error("Device contribution refresh error:", error));
};

const bindLogout = () => {
    document.querySelectorAll("[data-logout]").forEach((button) => {
        button.addEventListener("click", () => {
            sessionStorage.removeItem(TOKEN_KEY);
            sessionStorage.removeItem(USERNAME_KEY);
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

            message.textContent = "";
            message.classList.remove("success");

            try {
                const response = await fetch(`/api/auth/${mode}`, {
                    method: "POST",
                    headers: {
                        "Content-Type": "application/json"
                    },
                    body: JSON.stringify(payload)
                });

                if (!response.ok) {
                    throw new Error("Перевірте введені дані та спробуйте ще раз.");
                }

                const data = await response.json();
                sessionStorage.setItem(TOKEN_KEY, data.token);
                sessionStorage.setItem(USERNAME_KEY, data.username);
                setAuthCookie(data.token);

                updateAuthNavigation();
                message.classList.add("success");
                message.textContent = "Успішно. Переходимо до dashboard...";

                window.setTimeout(() => {
                    window.location.href = "/dashboard";
                }, 500);
            } catch (error) {
                message.textContent = error.message;
            }
        });
    });
};

const setMessage = (element, text, success = false) => {
    if (!element) {
        return;
    }

    element.textContent = text;
    element.classList.toggle("success", success);
};

const formatEnum = (value) => {
    if (!value) {
        return "-";
    }

    return value.toLowerCase().replaceAll("_", " ");
};

const formatNumber = (value, digits = 2) => {
    const number = Number(value);
    const safeNumber = Number.isFinite(number) ? number : 0;
    return safeNumber.toLocaleString("uk-UA", {
        minimumFractionDigits: digits,
        maximumFractionDigits: digits
    });
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
        list.innerHTML = `<p class="muted">Груп ще немає.</p>`;
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
        list.innerHTML = `<p class="muted">Пристроїв ще немає.</p>`;
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

    const households = await householdsResponse.json();
    const devices = await devicesResponse.json();

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
                throw new Error("Не вдалося створити групу.");
            }

            householdForm.reset();
            setMessage(householdMessage, "Групу створено.", true);
            await refreshDashboard();
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
                throw new Error("Не вдалося додати пристрій.");
            }

            deviceForm.reset();
            setMessage(deviceMessage, "Пристрій додано.", true);
            await refreshDashboard();
            window.setTimeout(refreshDashboard, 1500);
        } catch (error) {
            setMessage(deviceMessage, error.message);
        }
    });
};

const bindMLControls = () => {
    const simulateBtn = document.querySelector("[data-simulate-anomaly]");
    const message = document.querySelector("[data-ml-message]");

    simulateBtn?.addEventListener("click", async () => {
        const devices = document.querySelectorAll("[data-device-list] .entity-item");
        if (!devices.length) {
            setMessage(message, "Спочатку додайте пристрої.");
            return;
        }

        try {
            const devicesResponse = await apiFetch("/api/devices");
            const deviceList = await devicesResponse.json();
            if (!deviceList.length) {
                setMessage(message, "Пристроїв не знайдено.");
                return;
            }

            const deviceId = deviceList[0].id;
            const response = await apiFetch(
                `/api/ml/simulate-anomaly?deviceId=${deviceId}`,
                { method: "POST" }
            );

            if (response.ok) {
                setMessage(message, "Аномалію додано в журнал.", true);
                await loadAnomalies();
            }
        } catch (error) {
            setMessage(message, error.message);
        }
    });
};

const loadDeviceContribution = async () => {
    const list = document.querySelector("[data-device-contribution-list]");
    if (!list) {
        return;
    }

    try {
        const response = await apiFetch("/api/ml/device-contribution");
        if (!response.ok) {
            throw new Error("Не вдалося завантажити внесок пристроїв.");
        }

        const items = await response.json();
        if (!items.length) {
            list.innerHTML = `<p class="muted">Дані з’являться після додавання пристроїв.</p>`;
            return;
        }

        list.innerHTML = items.map((item) => `
            <article class="entity-item">
                <div>
                    <strong>${item.deviceName}</strong>
                    <span>${formatEnum(item.deviceType)} · ${formatNumber(item.totalKwh, 2)} кВт·год</span>
                </div>
                <span class="status-pill active">${formatNumber(item.percentage, 1)}%</span>
            </article>
        `).join("");
    } catch (error) {
        console.error("Device contribution error:", error);
        list.innerHTML = `<p class="muted">Внесок пристроїв тимчасово недоступний.</p>`;
    }
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
            return;
        }

        const anomalies = await response.json();

        if (!anomalies.length) {
            tbody.innerHTML = `<tr><td colspan="${columnCount}" class="empty-table">Аномалій не виявлено.</td></tr>`;
            return;
        }

        tbody.innerHTML = anomalies.map(a => `
            <tr>
                <td>${new Date(a.timestamp).toLocaleString("uk-UA")}</td>
                <td>${a.deviceName}</td>
                <td>${a.actualValue} кВт</td>
                <td>${a.expectedValue} кВт</td>
                <td>${a.deviationPercent}%</td>
                <td>
                    <span class="status-pill ${a.type === "SPIKE" ? "active" : ""}">
                        ${a.type === "SPIKE" ? "↑ Стрибок" : "↓ Падіння"}
                    </span>
                </td>
            </tr>
        `).join("");
    } catch (error) {
        console.error("Anomalies error:", error);
    }
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
            await loadDeviceContribution();
        } catch (error) {
            console.error("Auto refresh error:", error);
        }
    }, 10 * 60 * 1000); // кожні 10 хвилин
};

let consumptionChart = null;

const initConsumptionChart = async () => {
    const canvas = document.getElementById("consumptionChart");

    if (!canvas || typeof Chart === "undefined") {
        return;
    }

    // Знищити старий графік якщо є
    if (consumptionChart) {
        consumptionChart.destroy();
        consumptionChart = null;
    }

    let labels = [];
    let data = [];

    try {
        const response = await apiFetch("/api/ml/readings/hourly");
        if (response.ok) {
            const json = await response.json();
            if (json.labels?.length) {
                labels = json.labels.map(l => {
                    const d = new Date(l);
                    return `${String(d.getHours()).padStart(2, "0")}:${String(d.getMinutes()).padStart(2, "0")}`;
                });
                data = json.values;
            }
            const status = document.querySelector("[data-chart-status]");
            if (status) status.textContent = "Останні 24 години";
        }
    } catch (error) {
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
                    tension: 0.35,
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
                y: { beginAtZero: true }
            }
        }
    });
};

document.addEventListener("DOMContentLoaded", async () => {
    updateAuthNavigation();
    redirectAuthorizedGuest();
    redirectUnauthorizedUser();
    bindLogout();
    bindAuthForms();
    bindCoreForms();
    bindMLControls();
    await initConsumptionChart();

    try {
        await refreshDashboard();
        startAutoRefresh();
    } catch (error) {
        console.error(error);
    }
});
