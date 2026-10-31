// 데모 화면 스크립트입니다. 탭, 확인 대화상자, 도움말 패널 열고 닫기, Monaco 코드 보기·편집기를 담당합니다.
// htmx가 결과 조각을 끼워 넣으면 그 안의 코드 블록도 Monaco로 바꿉니다.
//
// <pre class="code" data-lang="json">...</pre>  읽기 전용 Monaco 보기. 화면에 보이게 될 때 만들어집니다.
// <textarea data-editor="yaml">...</textarea>   Monaco 편집기. 편집 내용을 원래 textarea(폼 필드)에 다시 써 넣습니다.
// Monaco가 없을 때(아직 불러오는 중이거나 불러오기에 실패한 경우)에도 원래의 <pre>/<textarea>는 그대로 쓸 수 있습니다.
(() => {
    // 편집기 한 줄의 높이(px)입니다. 내용 줄 수로 편집기 높이를 계산할 때 씁니다.
    const LINE_HEIGHT = 19;
    // 읽기 전용 보기의 최대 높이(px)입니다. data-max-height로 요소마다 바꿀 수 있습니다.
    const MAX_VIEW_HEIGHT = 480;
    // data-lang / data-editor 값을 Monaco 언어 이름으로 바꾸는 표입니다. 없는 값은 plaintext로 다룹니다.
    const LANGUAGES = {json: 'json', yaml: 'yaml', yml: 'yaml', java: 'java', csv: 'plaintext', text: 'plaintext', plaintext: 'plaintext'};

    // Monaco 로딩 Promise입니다. 한 번만 불러오도록 처음 만든 것을 계속 씁니다.
    let monacoReady = null;

    /**
     * Monaco 편집기를 처음 필요할 때 한 번만 불러옵니다.
     * 주소는 <meta name="monaco-base">에서 읽고, AMD 로더(loader.js)를 붙인 뒤 editor.main을 불러옵니다.
     * 불러오기에 실패하면 Promise가 reject됩니다.
     */
    function loadMonaco() {
        if (monacoReady) return monacoReady;
        const base = document.querySelector('meta[name="monaco-base"]').content;
        monacoReady = new Promise((resolve, reject) => {
            const script = document.createElement('script');
            script.src = base + '/loader.js';
            script.onload = () => {
                window.require.config({paths: {vs: base}});
                window.require(['vs/editor/editor.main'], () => resolve(window.monaco), reject);
            };
            script.onerror = reject;
            document.head.appendChild(script);
        });
        return monacoReady;
    }

    /** data-lang 등의 값을 Monaco 언어 이름으로 바꿉니다. 모르는 값이면 plaintext입니다. */
    function language(name) {
        return LANGUAGES[(name || '').toLowerCase()] || 'plaintext';
    }

    /**
     * 한 줄로 압축된 JSON(요청·응답 본문)을 들여쓰기해 보기 좋게 바꿉니다. 앞에 레이블이 붙은 텍스트("Created: {...}")는
     * 레이블을 따로 한 줄로 남깁니다. JSON으로 해석할 수 없는 텍스트는 그대로 둡니다.
     */
    function prettyJson(text) {
        const trimmed = text.trim();
        const start = trimmed.search(/[{[]/);
        if (start < 0) return text;
        try {
            const json = JSON.stringify(JSON.parse(trimmed.substring(start)), null, 2);
            const label = trimmed.substring(0, start).trim();
            return label ? label + '\n' + json : json;
        } catch (e) {
            return text;
        }
    }

    // 보기와 편집기가 함께 쓰는 Monaco 옵션입니다. 미니맵을 끄고, 마우스 휠이 끝에 닿으면 페이지가 스크롤되게 합니다.
    const baseOptions = {
        minimap: {enabled: false},
        scrollBeyondLastLine: false,
        automaticLayout: true,
        fontSize: 12.5,
        lineHeight: LINE_HEIGHT,
        tabSize: 2,
        wordWrap: 'on',
        renderLineHighlight: 'none',
        scrollbar: {alwaysConsumeMouseWheel: false},
        fixedOverflowWidgets: true,
    };

    /**
     * <pre class="code">를 읽기 전용 Monaco 보기로 바꿉니다.
     * JSON이면 먼저 보기 좋게 정리하고, 높이는 줄 수에 맞추되 최대 높이를 넘지 않게 합니다.
     * 도움말 패널 안에서는 줄 번호와 접기를 끄고 줄바꿈 없이 표시합니다.
     */
    function createView(monaco, pre) {
        const lang = language(pre.dataset.lang);
        const raw = pre.textContent.replace(/\s+$/, '');
        const text = lang === 'json' ? prettyJson(raw) : raw;
        const lines = text.split('\n').length;
        const max = Number(pre.dataset.maxHeight) || MAX_VIEW_HEIGHT;
        const inHelp = !!pre.closest('.help');
        const host = document.createElement('div');
        host.className = 'monaco-host';
        host.style.height = Math.min(lines * LINE_HEIGHT + 12, max) + 'px';
        pre.replaceWith(host);
        const editor = monaco.editor.create(host, {
            ...baseOptions,
            value: text,
            language: lang,
            readOnly: true,
            domReadOnly: true,
            lineNumbers: lines > 3 && !inHelp ? 'on' : 'off',
            folding: lines > 3 && !inHelp,
            lineDecorationsWidth: inHelp ? 6 : 10,
            // 도움말 열은 좁으므로, 줄을 맞춰 쓴 주석이 깨지지 않도록 줄바꿈 대신 가로로 스크롤합니다.
            wordWrap: inHelp ? 'off' : 'on',
            padding: {top: 6, bottom: 6},
        });
        // 줄바꿈된 줄 때문에 실제 내용 높이가 줄 수보다 커질 수 있으므로, 실제 내용 높이를 따라 다시 맞춥니다.
        const fit = () => host.style.height = Math.min(editor.getContentHeight(), max) + 'px';
        editor.onDidContentSizeChange(fit);
        fit();
    }

    /**
     * <textarea data-editor>를 Monaco 편집기로 바꿉니다. textarea는 숨겨 두고, 편집할 때마다 값을 다시 써 넣어
     * 폼을 제출하면 편집한 내용이 그대로 전송되게 합니다. 높이는 textarea의 rows로 정합니다.
     */
    function createEditor(monaco, textarea) {
        const host = document.createElement('div');
        host.className = 'monaco-host editor';
        host.style.height = (Number(textarea.getAttribute('rows')) || 10) * LINE_HEIGHT + 12 + 'px';
        textarea.style.display = 'none';
        textarea.after(host);
        const editor = monaco.editor.create(host, {
            ...baseOptions,
            value: textarea.value,
            language: language(textarea.dataset.editor),
        });
        editor.onDidChangeModelContent(() => textarea.value = editor.getValue());
        // <textarea data-editor-language-from="format">은 같은 폼의 이름이 "format"인 select 값에 따라 언어를 바꿉니다.
        const selectName = textarea.dataset.editorLanguageFrom;
        const select = selectName && textarea.form && textarea.form.elements[selectName];
        if (select) {
            const apply = () => monaco.editor.setModelLanguage(editor.getModel(), language(select.value));
            select.addEventListener('change', apply);
            apply();
        }
    }

    // 화면에 보일 때만 만듭니다. 페이지에는 닫힌 <details>나 숨은 탭 안에 본문이 많아서, 한꺼번에 만들면 느려지기 때문입니다.
    // IntersectionObserver가 없는 브라우저에서는 바로 만듭니다.
    const observer = 'IntersectionObserver' in window ? new IntersectionObserver(entries => {
        entries.filter(e => e.isIntersecting).forEach(e => {
            observer.unobserve(e.target);
            build(e.target);
        });
    }, {rootMargin: '200px'}) : null;

    /** Monaco를 불러온 뒤 요소에 맞는 보기나 편집기를 만듭니다. 그사이 요소가 페이지에서 빠졌으면 만들지 않습니다. */
    function build(element) {
        loadMonaco().then(monaco => {
            if (!element.isConnected) return;
            if (element.tagName === 'TEXTAREA') createEditor(monaco, element);
            else createView(monaco, element);
        }).catch(e => console.warn('Monaco editor not available; showing plain text', e));
    }

    /** root 아래의 코드 블록과 편집기를 Monaco로 바꿉니다. 나중에 추가된 행에도 씁니다. */
    function enhance(root) {
        root.querySelectorAll('pre.code, textarea[data-editor]').forEach(element => {
            if (observer) observer.observe(element);
            else build(element);
        });
    }

    // 다른 페이지 스크립트가 쓸 수 있도록 공개합니다.
    window.demoCode = {enhance};

    /**
     * 도움말 패널 열고 닫기를 설정합니다. 숨김 상태는 localStorage에 저장해 다음 페이지에서도 유지합니다.
     */
    function setUpHelp() {
        const KEY = 'ranger-client-demo.help-hidden';
        const apply = hidden => document.body.classList.toggle('help-hidden', hidden);
        let hidden = false;
        try {
            hidden = localStorage.getItem(KEY) === 'true';
        } catch (e) {
            // 저장소를 쓸 수 없으면 도움말을 보이는 상태로 둡니다.
        }
        apply(hidden);
        document.querySelectorAll('[data-help-toggle]').forEach(button => button.addEventListener('click', () => {
            hidden = !document.body.classList.contains('help-hidden');
            apply(hidden);
            try {
                localStorage.setItem(KEY, String(hidden));
            } catch (e) {
                // 저장하지 못해도 현재 페이지에서는 이미 적용되었으므로 무시합니다.
            }
        }));
    }

    document.addEventListener('DOMContentLoaded', () => {
        // 탭: <div data-tabs> 안의 data-tab="id" 버튼과 id="id" 패널을 짝지어, 누른 탭만 보여 줍니다. 처음에는 첫 탭을 엽니다.
        document.querySelectorAll('[data-tabs]').forEach(tabs => {
            const buttons = tabs.querySelectorAll('[data-tab]');
            const show = id => buttons.forEach(b => {
                b.classList.toggle('active', b.dataset.tab === id);
                document.getElementById(b.dataset.tab).classList.toggle('active', b.dataset.tab === id);
            });
            buttons.forEach(b => b.addEventListener('click', () => show(b.dataset.tab)));
            if (buttons.length) show(buttons[0].dataset.tab);
        });
        // data-confirm이 있는 폼은 제출 전에 확인을 받고, 취소하면 제출하지 않습니다.
        document.querySelectorAll('form[data-confirm]').forEach(f => f.addEventListener('submit', e => {
            if (!confirm(f.dataset.confirm)) e.preventDefault();
        }));
        setUpHelp();
        enhance(document);
        // htmx가 끼워 넣은 결과 조각의 코드 블록과 편집기도 Monaco로 바꿉니다.
        document.body.addEventListener('htmx:afterSettle', e => enhance(e.detail.elt));
    });
})();
