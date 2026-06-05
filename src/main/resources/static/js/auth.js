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
    const protectedPaths = ["/dashboard", "/devices", "/profile", "/anomalies", "/simulation"];
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
                message.textContent = "Успішно. Переходимо до огляду...";

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

    const labels = {
        APARTMENT: "Квартира",
        HOUSE: "Будинок",
        OFFICE: "Офіс",
        GARAGE: "Гараж",
        COTTAGE: "Дача",
        FRIDGE: "Холодильник",
        REFRIGERATOR: "Холодильник",
        FREEZER: "Морозильна камера",
        TV: "Телевізор",
        AC: "Кондиціонер",
        HEATER: "Обігрівач",
        BOILER: "Електричний бойлер",
        GAS_BOILER: "Газовий котел",
        WASHING_MACHINE: "Пральна машина",
        DRYER: "Сушильна машина",
        DISHWASHER: "Посудомийка",
        OVEN: "Духовка",
        STOVE: "Плита",
        MICROWAVE: "Мікрохвильовка",
        KETTLE: "Чайник",
        COFFEE_MACHINE: "Кавомашина",
        TOASTER: "Тостер",
        IRON: "Праска",
        HAIR_DRYER: "Фен",
        LIGHT: "Освітлення",
        LIGHTING: "Освітлення",
        COMPUTER: "Комп'ютер",
        DESKTOP_PC: "Стаціонарний ПК",
        LAPTOP: "Ноутбук",
        MONITOR: "Монітор",
        GAME_CONSOLE: "Ігрова приставка",
        SPEAKERS: "Акустика",
        ROUTER: "Роутер",
        CHARGER: "Зарядний пристрій",
        AIR_PURIFIER: "Очищувач повітря",
        FAN: "Вентилятор",
        VACUUM: "Пилосос",
        WATER_PUMP: "Насос",
        EV_CHARGER: "Зарядка авто",
        SOLAR_INVERTER: "Сонячний інвертор",
        BATTERY_STORAGE: "Акумулятор",
        SMART_PLUG: "Розумна розетка",
        CONSTANT: "Постійна",
        CYCLIC: "Циклічна",
        INTERMITTENT: "Переривчаста",
        PEAK_BASED: "Пікова",
        OTHER: "Інше"
    };

    if (labels[value]) {
        return labels[value];
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

let cachedDevices = [];

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
    cachedDevices = devices;

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

    const canManage = list.hasAttribute("data-manage-devices");

    list.innerHTML = devices.map((device) => `
        <article class="entity-item">
            <div>
                <strong>${device.name}</strong>
                <span>${formatEnum(device.type)} · ${formatEnum(device.behaviorProfile)}</span>
                <small>${device.householdName || "Без групи"}${device.nominalPower ? ` · ${device.nominalPower} кВт` : ""}</small>
            </div>
            <div class="entity-actions">
                <span class="status-pill ${device.active ? "active" : ""}">${device.active ? "active" : "inactive"}</span>
                ${canManage ? `
                    <button class="text-button" type="button" data-device-edit="${device.id}">Редагувати</button>
                    <button class="text-button" type="button" data-device-toggle="${device.id}">${device.active ? "Вимкнути" : "Увімкнути"}</button>
                    <button class="text-button danger" type="button" data-device-delete="${device.id}">Видалити</button>
                ` : ""}
            </div>
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

const devicePayloadFromForm = (deviceForm) => {
    const payload = Object.fromEntries(new FormData(deviceForm).entries());

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

    payload.active = payload.active === "false" ? false : true;
    return payload;
};

const resetDeviceForm = (deviceForm) => {
    if (!deviceForm) {
        return;
    }

    delete deviceForm.dataset.editingDeviceId;
    deviceForm.reset();
    if (deviceForm.elements.active) {
        deviceForm.elements.active.value = "true";
    }

    const title = document.querySelector("[data-device-form-title]");
    const submit = document.querySelector("[data-device-submit]");
    const cancel = document.querySelector("[data-device-cancel]");

    if (title) {
        title.textContent = "Додати пристрій";
    }
    if (submit) {
        submit.textContent = "Додати пристрій";
    }
    if (cancel) {
        cancel.hidden = true;
    }
};

const fillDeviceForm = (deviceForm, device) => {
    if (!deviceForm || !device) {
        return;
    }

    deviceForm.dataset.editingDeviceId = String(device.id);
    deviceForm.elements.householdId.value = device.householdId ?? "";
    deviceForm.elements.name.value = device.name ?? "";
    deviceForm.elements.type.value = device.type ?? "OTHER";
    deviceForm.elements.behaviorProfile.value = device.behaviorProfile ?? "";
    deviceForm.elements.nominalPower.value = device.nominalPower ?? "";
    if (deviceForm.elements.active) {
        deviceForm.elements.active.value = device.active ? "true" : "false";
    }

    const title = document.querySelector("[data-device-form-title]");
    const submit = document.querySelector("[data-device-submit]");
    const cancel = document.querySelector("[data-device-cancel]");

    if (title) {
        title.textContent = "Редагувати пристрій";
    }
    if (submit) {
        submit.textContent = "Зберегти зміни";
    }
    if (cancel) {
        cancel.hidden = false;
    }

    deviceForm.scrollIntoView({ behavior: "smooth", block: "start" });
};

const updateDeviceFromList = async (device, changes, message) => {
    const payload = {
        name: device.name,
        householdId: device.householdId,
        type: device.type,
        behaviorProfile: device.behaviorProfile,
        nominalPower: device.nominalPower,
        active: device.active,
        ...changes
    };

    if (!payload.behaviorProfile) {
        delete payload.behaviorProfile;
    }

    try {
        const response = await apiFetch(`/api/devices/${device.id}`, {
            method: "PUT",
            body: JSON.stringify(payload)
        });
        if (!response.ok) {
            throw new Error("Не вдалося змінити статус пристрою.");
        }

        setMessage(message, payload.active ? "Пристрій увімкнено." : "Пристрій вимкнено.", true);
        await refreshDashboard();
    } catch (error) {
        setMessage(message, error.message);
    }
};

const bindCoreForms = () => {
    const householdForm = document.querySelector("[data-household-form]");
    const householdMessage = document.querySelector("[data-household-message]");
    const deviceForm = document.querySelector("[data-device-form]");
    const deviceMessage = document.querySelector("[data-device-message]");
    const deviceCancel = document.querySelector("[data-device-cancel]");

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

        const payload = devicePayloadFromForm(deviceForm);
        const editingId = deviceForm.dataset.editingDeviceId;
        const url = editingId ? `/api/devices/${editingId}` : "/api/devices";
        const method = editingId ? "PUT" : "POST";

        try {
            const response = await apiFetch(url, {
                method,
                body: JSON.stringify(payload)
            });

            if (!response.ok) {
                throw new Error(editingId ? "Не вдалося оновити пристрій." : "Не вдалося додати пристрій.");
            }

            resetDeviceForm(deviceForm);
            setMessage(deviceMessage, editingId ? "Пристрій оновлено." : "Пристрій додано.", true);
            await refreshDashboard();
            window.setTimeout(refreshDashboard, 1500);
        } catch (error) {
            setMessage(deviceMessage, error.message);
        }
    });

    deviceCancel?.addEventListener("click", () => {
        resetDeviceForm(deviceForm);
        setMessage(deviceMessage, "");
    });

    document.querySelector("[data-device-list]")?.addEventListener("click", async (event) => {
        const editButton = event.target.closest("[data-device-edit]");
        const toggleButton = event.target.closest("[data-device-toggle]");
        const deleteButton = event.target.closest("[data-device-delete]");

        if (editButton) {
            const device = cachedDevices.find((item) => String(item.id) === String(editButton.dataset.deviceEdit));
            if (device) {
                fillDeviceForm(deviceForm, device);
                setMessage(deviceMessage, "");
            }
            return;
        }

        if (toggleButton) {
            const device = cachedDevices.find((item) => String(item.id) === String(toggleButton.dataset.deviceToggle));
            if (device) {
                await updateDeviceFromList(device, { active: !device.active }, deviceMessage);
            }
            return;
        }

        if (deleteButton) {
            const device = cachedDevices.find((item) => String(item.id) === String(deleteButton.dataset.deviceDelete));
            if (!device) {
                return;
            }

            if (!window.confirm(`Видалити пристрій "${device.name}"?`)) {
                return;
            }

            try {
                const response = await apiFetch(`/api/devices/${device.id}`, { method: "DELETE" });
                if (!response.ok) {
                    throw new Error("Не вдалося видалити пристрій.");
                }
                resetDeviceForm(deviceForm);
                setMessage(deviceMessage, "Пристрій видалено.", true);
                await refreshDashboard();
            } catch (error) {
                setMessage(deviceMessage, error.message);
            }
        }
    });
};

const normalizeTimeForInput = (value) => {
    if (!value) {
        return "";
    }
    return String(value).slice(0, 5);
};

const MAX_CUSTOM_PERIODS = 5;

const hasCustomPeriodData = (profile, index) => (
    Boolean(profile?.[`customHomeStart${index}`])
    || Boolean(profile?.[`customHomeEnd${index}`])
    || Boolean(profile?.[`customHomeHeavyAllowed${index}`])
);

const resolveVisibleCustomPeriodCount = (profile) => {
    let visibleCount = 1;
    for (let index = 2; index <= MAX_CUSTOM_PERIODS; index += 1) {
        if (hasCustomPeriodData(profile, index)) {
            visibleCount = index;
        }
    }
    return visibleCount;
};

const setCustomPeriodVisibility = (form, visibleCount) => {
    const section = document.querySelector("[data-custom-schedule-section]");
    if (!form || !section) {
        return;
    }

    const safeCount = Math.max(1, Math.min(MAX_CUSTOM_PERIODS, Number(visibleCount) || 1));
    section.dataset.visiblePeriods = String(safeCount);

    section.querySelectorAll("[data-custom-period]").forEach((period) => {
        const index = Number(period.dataset.customPeriod);
        const isVisible = index <= safeCount;
        period.hidden = !isVisible;

        if (index > 1 && !period.querySelector("[data-remove-custom-period]")) {
            const heading = period.querySelector("h3");
            const removeButton = document.createElement("button");
            removeButton.type = "button";
            removeButton.className = "custom-period-remove";
            removeButton.dataset.removeCustomPeriod = String(index);
            removeButton.textContent = "Видалити";
            heading?.append(removeButton);
        }

        const removeButton = period.querySelector("[data-remove-custom-period]");
        if (removeButton) {
            removeButton.hidden = !isVisible || section.hidden;
        }

        period.querySelectorAll("input, select").forEach((control) => {
            control.disabled = !isVisible || section.hidden;
        });
    });

    const addButton = section.querySelector("[data-add-custom-period]");
    if (addButton) {
        addButton.hidden = safeCount >= MAX_CUSTOM_PERIODS;
        addButton.disabled = section.hidden;
    }

    const counter = section.querySelector("[data-custom-period-count]");
    if (counter) {
        counter.textContent = `${safeCount} з ${MAX_CUSTOM_PERIODS} періодів`;
    }
};

const customPeriodValues = (form, index) => ({
    start: form.elements[`customHomeStart${index}`]?.value || "",
    end: form.elements[`customHomeEnd${index}`]?.value || "",
    activity: form.elements[`customHomeActivity${index}`]?.value || "NORMAL",
    heavyAllowed: Boolean(form.elements[`customHomeHeavyAllowed${index}`]?.checked)
});

const setCustomPeriodValues = (form, index, values) => {
    if (form.elements[`customHomeStart${index}`]) {
        form.elements[`customHomeStart${index}`].value = values.start || "";
    }
    if (form.elements[`customHomeEnd${index}`]) {
        form.elements[`customHomeEnd${index}`].value = values.end || "";
    }
    if (form.elements[`customHomeActivity${index}`]) {
        form.elements[`customHomeActivity${index}`].value = values.activity || "NORMAL";
    }
    if (form.elements[`customHomeHeavyAllowed${index}`]) {
        form.elements[`customHomeHeavyAllowed${index}`].checked = Boolean(values.heavyAllowed);
    }
};

const clearCustomPeriod = (form, index) => {
    setCustomPeriodValues(form, index, {
        start: "",
        end: "",
        activity: "NORMAL",
        heavyAllowed: false
    });
};

const removeCustomPeriod = (form, removeIndex) => {
    const section = document.querySelector("[data-custom-schedule-section]");
    if (!form || !section || removeIndex <= 1) {
        return;
    }

    const visibleCount = Number(section.dataset.visiblePeriods) || 1;
    if (removeIndex > visibleCount) {
        return;
    }

    for (let index = removeIndex; index < visibleCount; index += 1) {
        setCustomPeriodValues(form, index, customPeriodValues(form, index + 1));
    }
    clearCustomPeriod(form, visibleCount);
    setCustomPeriodVisibility(form, visibleCount - 1);
};

const toggleCustomScheduleSection = (form) => {
    const section = document.querySelector("[data-custom-schedule-section]");
    if (!form || !section) {
        return;
    }

    const isCustom = form.elements.presenceMode?.value === "CUSTOM";
    section.hidden = !isCustom;
    setCustomPeriodVisibility(form, Number(section.dataset.visiblePeriods) || 1);
};

const normalizeRegionValue = (value) => {
    if (!value) {
        return "Київська";
    }

    const normalized = String(value).trim().toLowerCase();
    const aliases = {
        kyiv: "Київська",
        kiev: "Київська",
        київ: "Київська",
        київська: "Київська",
        odesa: "Одеська",
        odessa: "Одеська",
        одеса: "Одеська",
        одеська: "Одеська",
        lviv: "Львівська",
        львів: "Львівська",
        львівська: "Львівська"
    };

    return aliases[normalized] || value;
};

const fillSimulationForm = (profile) => {
    const form = document.querySelector("[data-simulation-form]");
    if (!form || !profile) {
        return;
    }

    form.elements.occupants.value = profile.occupants ?? 2;
    form.elements.areaM2.value = profile.areaM2 ?? 55;
    form.elements.city.value = normalizeRegionValue(profile.city);
    if (!form.elements.city.value) {
        form.elements.city.value = "Київська";
    }
    form.elements.activityLevel.value = profile.activityLevel ?? "NORMAL";
    form.elements.presenceMode.value = profile.presenceMode ?? "STANDARD_WORKDAY";
    form.elements.sleepStart.value = normalizeTimeForInput(profile.sleepStart) || "23:30";
    form.elements.sleepEnd.value = normalizeTimeForInput(profile.sleepEnd) || "07:00";
    form.elements.awayStart.value = normalizeTimeForInput(profile.awayStart) || "09:00";
    form.elements.awayEnd.value = normalizeTimeForInput(profile.awayEnd) || "17:30";
    form.elements.customHomeStart1.value = normalizeTimeForInput(profile.customHomeStart1) || "07:00";
    form.elements.customHomeEnd1.value = normalizeTimeForInput(profile.customHomeEnd1) || "09:00";
    form.elements.customHomeActivity1.value = profile.customHomeActivity1 ?? "NORMAL";
    form.elements.customHomeHeavyAllowed1.checked = Boolean(profile.customHomeHeavyAllowed1);
    form.elements.customHomeStart2.value = normalizeTimeForInput(profile.customHomeStart2);
    form.elements.customHomeEnd2.value = normalizeTimeForInput(profile.customHomeEnd2);
    form.elements.customHomeActivity2.value = profile.customHomeActivity2 ?? "NORMAL";
    form.elements.customHomeHeavyAllowed2.checked = Boolean(profile.customHomeHeavyAllowed2);
    form.elements.customHomeStart3.value = normalizeTimeForInput(profile.customHomeStart3);
    form.elements.customHomeEnd3.value = normalizeTimeForInput(profile.customHomeEnd3);
    form.elements.customHomeActivity3.value = profile.customHomeActivity3 ?? "NORMAL";
    form.elements.customHomeHeavyAllowed3.checked = Boolean(profile.customHomeHeavyAllowed3);
    form.elements.customHomeStart4.value = normalizeTimeForInput(profile.customHomeStart4);
    form.elements.customHomeEnd4.value = normalizeTimeForInput(profile.customHomeEnd4);
    form.elements.customHomeActivity4.value = profile.customHomeActivity4 ?? "NORMAL";
    form.elements.customHomeHeavyAllowed4.checked = Boolean(profile.customHomeHeavyAllowed4);
    form.elements.customHomeStart5.value = normalizeTimeForInput(profile.customHomeStart5);
    form.elements.customHomeEnd5.value = normalizeTimeForInput(profile.customHomeEnd5);
    form.elements.customHomeActivity5.value = profile.customHomeActivity5 ?? "NORMAL";
    form.elements.customHomeHeavyAllowed5.checked = Boolean(profile.customHomeHeavyAllowed5);

    const weekendDays = (profile.weekendDays ?? "SATURDAY,SUNDAY")
        .split(",")
        .map(day => day.trim())
        .filter(day => day && day !== "NONE");

    form.querySelectorAll('input[name="weekendDay"]').forEach((checkbox) => {
        checkbox.checked = weekendDays.includes(checkbox.value);
    });

    setCustomPeriodVisibility(form, resolveVisibleCustomPeriodCount(profile));
    toggleCustomScheduleSection(form);
};

const loadSimulationProfile = async () => {
    const form = document.querySelector("[data-simulation-form]");
    if (!form) {
        return;
    }

    const response = await apiFetch("/api/simulation-profile");
    if (!response.ok) {
        throw new Error("Не вдалося завантажити профіль симуляції.");
    }

    fillSimulationForm(await response.json());
};

const simulationPayloadFromForm = (form) => {
    const payload = Object.fromEntries(new FormData(form).entries());
    payload.occupants = Number(payload.occupants || 2);
    payload.areaM2 = Number(payload.areaM2 || 55);
    const weekendDays = Array.from(form.querySelectorAll('input[name="weekendDay"]:checked'))
        .map(checkbox => checkbox.value);
    payload.weekendDays = weekendDays.length ? weekendDays.join(",") : "NONE";
    const visibleCount = Number(document.querySelector("[data-custom-schedule-section]")?.dataset.visiblePeriods) || 1;
    for (let index = 1; index <= 5; index += 1) {
        const heavyControl = form.elements[`customHomeHeavyAllowed${index}`];
        if (index > visibleCount) {
            delete payload[`customHomeStart${index}`];
            delete payload[`customHomeEnd${index}`];
            delete payload[`customHomeActivity${index}`];
            payload[`customHomeHeavyAllowed${index}`] = false;
            continue;
        }
        payload[`customHomeHeavyAllowed${index}`] = Boolean(heavyControl?.checked);
        if (!payload[`customHomeStart${index}`]) {
            delete payload[`customHomeStart${index}`];
        }
        if (!payload[`customHomeEnd${index}`]) {
            delete payload[`customHomeEnd${index}`];
        }
    }
    delete payload.weekendDay;
    return payload;
};

const bindSimulationProfile = () => {
    const form = document.querySelector("[data-simulation-form]");
    const message = document.querySelector("[data-simulation-message]");
    const regenerateButton = document.querySelector("[data-simulation-regenerate]");

    toggleCustomScheduleSection(form);

    form?.elements.presenceMode?.addEventListener("change", () => {
        toggleCustomScheduleSection(form);
    });

    document.querySelector("[data-add-custom-period]")?.addEventListener("click", () => {
        const section = document.querySelector("[data-custom-schedule-section]");
        const visibleCount = Number(section?.dataset.visiblePeriods) || 1;
        setCustomPeriodVisibility(form, visibleCount + 1);
    });

    document.querySelector("[data-custom-schedule-section]")?.addEventListener("click", (event) => {
        const removeButton = event.target.closest("[data-remove-custom-period]");
        if (!removeButton) {
            return;
        }

        removeCustomPeriod(form, Number(removeButton.dataset.removeCustomPeriod));
    });

    form?.addEventListener("submit", async (event) => {
        event.preventDefault();
        setMessage(message, "");

        try {
            const response = await apiFetch("/api/simulation-profile", {
                method: "PUT",
                body: JSON.stringify(simulationPayloadFromForm(form))
            });

            if (!response.ok) {
                throw new Error("Не вдалося зберегти профіль симуляції.");
            }

            fillSimulationForm(await response.json());
            setMessage(message, "Сценарій збережено.", true);
        } catch (error) {
            setMessage(message, error.message);
        }
    });

    regenerateButton?.addEventListener("click", async () => {
        setMessage(message, "Оновлюємо дані за останні 24 години...");
        regenerateButton.disabled = true;

        try {
            const response = await apiFetch("/api/simulation-profile/regenerate-recent", { method: "POST" });
            if (!response.ok) {
                throw new Error("Не вдалося перегенерувати дані.");
            }

            const result = await response.json();
            setMessage(message, `Готово. Оновлено ${result.created ?? 0} записів споживання.`, true);
        } catch (error) {
            setMessage(message, error.message);
        } finally {
            regenerateButton.disabled = false;
        }
    });
};

const bindMLControls = () => {
    const simulateBtn = document.querySelector("[data-simulate-anomaly]");
    const message = document.querySelector("[data-ml-message]");

    simulateBtn?.addEventListener("click", async () => {
        try {
            const response = await apiFetch("/api/ml/simulate-anomaly", { method: "POST" });

            if (response.ok) {
                const result = await response.json();
                const deviceName = result.deviceName ? ` для "${result.deviceName}"` : "";
                setMessage(message, `Аномалію додано в журнал${deviceName}.`, true);
                await loadAnomalies();
            } else {
                setMessage(message, "Спочатку додайте активні пристрої.");
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
    bindSimulationProfile();
    bindMLControls();
    await initConsumptionChart();

    try {
        await loadSimulationProfile();
        await refreshDashboard();
        startAutoRefresh();
    } catch (error) {
        console.error(error);
    }
});
