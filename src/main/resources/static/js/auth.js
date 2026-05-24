const TOKEN_KEY = "energy-monitor-token";
const USERNAME_KEY = "energy-monitor-username";
const TOKEN_MAX_AGE_SECONDS = 60 * 60 * 24;

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
        ...(options.body ? { "Content-Type": "application/json" } : {}),
        ...(token ? { Authorization: `Bearer ${token}` } : {}),
        ...(options.headers || {})
    };

    const response = await fetch(url, { ...options, headers });
    if (response.status === 401) {
        localStorage.removeItem(TOKEN_KEY);
        localStorage.removeItem(USERNAME_KEY);
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
                localStorage.setItem(TOKEN_KEY, data.token);
                localStorage.setItem(USERNAME_KEY, data.username);
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
                throw new Error("Не вдалося додати пристрій.");
            }

            deviceForm.reset();
            setMessage(deviceMessage, "Пристрій додано.", true);
            await loadCoreStructure();
        } catch (error) {
            setMessage(deviceMessage, error.message);
        }
    });
};

const initConsumptionChart = () => {
    const canvas = document.getElementById("consumptionChart");

    if (!canvas || typeof Chart === "undefined") {
        return;
    }

    new Chart(canvas, {
        type: "line",
        data: {
            labels: ["00:00", "04:00", "08:00", "12:00", "16:00", "20:00"],
            datasets: [
                {
                    label: "Споживання",
                    data: [0, 0, 0, 0, 0, 0],
                    borderColor: "#0f766e",
                    backgroundColor: "rgba(15, 118, 110, 0.12)",
                    fill: true,
                    tension: 0.35
                }
            ]
        },
        options: {
            responsive: true,
            maintainAspectRatio: false,
            plugins: {
                legend: {
                    display: false
                }
            },
            scales: {
                y: {
                    beginAtZero: true
                }
            }
        }
    });
};

document.addEventListener("DOMContentLoaded", async () => {
    updateAuthNavigation();
    redirectAuthorizedGuest();
    bindLogout();
    bindAuthForms();
    bindCoreForms();
    initConsumptionChart();

    try {
        await loadCoreStructure();
    } catch (error) {
        console.error(error);
    }
});
