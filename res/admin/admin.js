'use strict';
const normalizeText = value => value.normalize('NFD').replace(/[\u0300-\u036f]/g, '').replace(/đ/g, 'd').replace(/Đ/g, 'D').toLowerCase();
document.querySelectorAll('.picker').forEach(picker => {
    const input = picker.querySelector('.catalog-search');
    const select = picker.querySelector('select');
    const count = picker.querySelector('.picker-count');
    const preview = picker.querySelector('.picker-preview');
    const options = Array.from(select.options).map(option => option.cloneNode(true));
    const updatePreview = () => {
        if (!preview) return;
        preview.replaceChildren();
        const option = select.selectedOptions[0];
        if (!option || !option.value) return;
        if (/^ITEM:\d+$/.test(option.value)) {
            const img = document.createElement('img');
            img.src = '/admin/icons/' + option.value.split(':')[1] + '.png';
            img.alt = option.textContent;
            img.addEventListener('error', () => img.remove());
            preview.append(img);
        }
        const text = document.createElement('span');
        text.textContent = option.dataset.detail || option.textContent;
        preview.append(text);
    };
    input.addEventListener('input', () => {
        const previous = select.value;
        const term = normalizeText(input.value.trim());
        const found = options.filter(option => !option.value || normalizeText(option.textContent).includes(term));
        select.replaceChildren(...found.map(option => option.cloneNode(true)));
        select.value = found.some(option => option.value === previous) ? previous : '';
        count.textContent = (found.length - 1) + ' kết quả';
        updatePreview();
    });
    select.addEventListener('change', updatePreview);
    updatePreview();
});
document.querySelectorAll('form[method="post"]').forEach(form => {
    form.addEventListener('submit', () => {
        form.querySelectorAll('button[type="submit"], button:not([type])').forEach(button => {
            button.disabled = true;
            button.textContent = 'Đang xử lý…';
        });
    });
});
