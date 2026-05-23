const forms = document.querySelectorAll("[data-auth-form]");

forms.forEach((form) => {
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
            localStorage.setItem("energy-monitor-token", data.token);
            localStorage.setItem("energy-monitor-username", data.username);

            message.classList.add("success");
            message.textContent = "Успішно. Переходимо на головну сторінку...";
            window.setTimeout(() => {
                window.location.href = "/";
            }, 500);
        } catch (error) {
            message.textContent = error.message;
        }
    });
});
