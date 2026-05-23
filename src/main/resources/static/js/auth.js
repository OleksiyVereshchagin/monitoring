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

document.addEventListener("DOMContentLoaded", () => {
    updateAuthNavigation();
    redirectAuthorizedGuest();
    bindLogout();
    bindAuthForms();
    initConsumptionChart();
});
