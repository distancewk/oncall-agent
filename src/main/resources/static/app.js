// SuperBizAgent 前端应用
class SuperBizAgentApp {
    constructor() {
        // 动态API基础URL - 自动适配当前环境
        this.apiBaseUrl = this.detectApiBaseUrl();
        this.currentMode = 'quick'; // 'quick' 或 'stream'
        this.sessionId = this.generateSessionId();
        this.isStreaming = false;
        this.currentChatHistory = []; // 当前对话的消息历史
        this.chatHistories = this.loadChatHistories(); // 所有历史对话
        this.isCurrentChatFromHistory = false; // 标记当前对话是否是从历史记录加载的
        this.alertEventSource = null; // SSE连接实例
        this.pendingAlertId = null; // 待查看的告警ID

        this.initializeElements();
        this.initTheme();
        this.bindEvents();
        this.updateUI();
        this.initMarkdown();
        this.checkAndSetCentered();
        this.renderChatHistory();
        this.connectAlertSSE();
        this.setupMobileSidebar();
    }

    // 动态检测API基础URL
    detectApiBaseUrl() {
        const protocol = window.location.protocol;
        const host = window.location.hostname;
        const port = window.location.port;
        // 如果在开发环境（localhost:9900），使用完整URL，否则使用相对路径
        if (host === 'localhost' || host === '127.0.0.1') {
            return `${protocol}//${host}:9900/api`;
        }
        return '/api';
    }

    // ==================== Markdown ====================

    // 初始化Markdown配置 (marked v11+使用非标准highlight方式)
    initMarkdown() {
        const checkMarked = () => {
            if (typeof marked !== 'undefined') {
                try {
                    marked.setOptions({
                        breaks: true,
                        gfm: true,
                        headerIds: false,
                        mangle: false
                    });
                    if (typeof hljs !== 'undefined') {
                        // marked v11+ 不再支持 highlight 选项，使用扩展或手动高亮
                        console.log('Markdown 和代码高亮库初始化成功');
                    }
                } catch (e) {
                    console.error('Markdown 配置失败:', e);
                }
            } else {
                setTimeout(checkMarked, 100);
            }
        };
        checkMarked();
    }

    // 安全地渲染 Markdown
    renderMarkdown(content) {
        if (!content) return '';
        if (typeof marked === 'undefined') {
            return this.escapeHtml(content);
        }
        try {
            return marked.parse(content);
        } catch (e) {
            console.error('Markdown 渲染失败:', e);
            return this.escapeHtml(content);
        }
    }

    // 高亮代码块
    highlightCodeBlocks(container) {
        if (typeof hljs !== 'undefined' && container) {
            try {
                container.querySelectorAll('pre code:not(.hljs)').forEach((block) => {
                    hljs.highlightElement(block);
                });
            } catch (e) {
                // 静默处理高亮失败
            }
        }
    }

    // ==================== DOM初始化 ====================

    initializeElements() {
        // 侧边栏元素
        this.sidebar = document.querySelector('.sidebar');
        this.newChatBtn = document.getElementById('newChatBtn');
        this.sidebarBackdrop = document.getElementById('sidebarBackdrop');
        this.themeToggleBtn = document.getElementById('themeToggleBtn');
        this.themeIconSun = document.getElementById('themeIconSun');
        this.themeIconMoon = document.getElementById('themeIconMoon');

        // 输入区域元素
        this.messageInput = document.getElementById('messageInput');
        this.sendButton = document.getElementById('sendButton');
        this.toolsBtn = document.getElementById('toolsBtn');
        this.toolsMenu = document.getElementById('toolsMenu');
        this.uploadFileItem = document.getElementById('uploadFileItem');
        this.modeSelectorBtn = document.getElementById('modeSelectorBtn');
        this.modeDropdown = document.getElementById('modeDropdown');
        this.currentModeText = document.getElementById('currentModeText');
        this.fileInput = document.getElementById('fileInput');

        // 聊天区域元素
        this.chatMessages = document.getElementById('chatMessages');
        this.loadingOverlay = document.getElementById('loadingOverlay');
        this.loadingText = document.getElementById('loadingText');
        this.loadingSubtext = document.getElementById('loadingSubtext');
        this.chatContainer = document.querySelector('.chat-container');
        this.welcomeGreeting = document.getElementById('welcomeGreeting');
        this.chatHistoryList = document.getElementById('chatHistoryList');

        // 告警相关元素
        this.queryAlertsBtn = document.getElementById('queryAlertsBtn');
        this.alertHistoryBtn = document.getElementById('alertHistoryBtn');
        this.simulateAlertBtn = document.getElementById('simulateAlertBtn');
        this.alertHistoryPanel = document.getElementById('alertHistoryPanel');
        this.alertHistoryContent = document.getElementById('alertHistoryContent');
        this.alertHistoryPanelClose = document.getElementById('alertHistoryPanelClose');
        this.alertDetailPanel = document.getElementById('alertDetailPanel');
        this.alertDetailContent = document.getElementById('alertDetailContent');
        this.alertDetailPanelClose = document.getElementById('alertDetailPanelClose');
    }

    // ==================== 事件绑定 ====================

    bindEvents() {
        // 新建对话
        if (this.newChatBtn) {
            this.newChatBtn.addEventListener('click', () => this.newChat());
        }

        // 模式选择下拉菜单
        if (this.modeSelectorBtn) {
            this.modeSelectorBtn.addEventListener('click', (e) => {
                e.stopPropagation();
                this.toggleModeDropdown();
            });
        }

        // 下拉菜单项点击
        document.querySelectorAll('.dropdown-item').forEach(item => {
            item.addEventListener('click', () => {
                const mode = item.getAttribute('data-mode');
                if (mode) {
                    this.selectMode(mode);
                    this.closeModeDropdown();
                }
            });
        });

        // 点击外部关闭下拉菜单
        document.addEventListener('click', (e) => {
            if (this.modeSelectorBtn && this.modeDropdown &&
                !this.modeSelectorBtn.contains(e.target) &&
                !this.modeDropdown.contains(e.target)) {
                this.closeModeDropdown();
            }
        });

        // 发送消息
        if (this.sendButton) {
            this.sendButton.addEventListener('click', () => this.sendMessage());
        }

        if (this.messageInput) {
            this.messageInput.addEventListener('keydown', (e) => {
                if (e.key === 'Enter' && !e.shiftKey) {
                    e.preventDefault();
                    this.sendMessage();
                }
            });
            // 输入框自动调整高度
            this.messageInput.addEventListener('input', () => this.autoResizeInput());
        }

        // 工具按钮和菜单
        if (this.toolsBtn) {
            this.toolsBtn.addEventListener('click', (e) => {
                e.stopPropagation();
                this.toggleToolsMenu();
            });
        }

        if (this.uploadFileItem) {
            this.uploadFileItem.addEventListener('click', () => {
                if (this.fileInput) this.fileInput.click();
                this.closeToolsMenu();
            });
        }

        // 点击外部关闭工具菜单
        document.addEventListener('click', (e) => {
            if (this.toolsBtn && this.toolsMenu &&
                !this.toolsBtn.contains(e.target) &&
                !this.toolsMenu.contains(e.target)) {
                this.closeToolsMenu();
            }
        });

        if (this.fileInput) {
            this.fileInput.addEventListener('change', (e) => this.handleFileSelect(e));
        }

        // 告警相关事件绑定
        if (this.queryAlertsBtn) {
            this.queryAlertsBtn.addEventListener('click', () => this.triggerAIOps());
        }
        if (this.alertHistoryBtn) {
            this.alertHistoryBtn.addEventListener('click', () => this.showAlertHistory());
        }
        if (this.simulateAlertBtn) {
            this.simulateAlertBtn.addEventListener('click', () => this.simulateAlert());
        }
        if (this.alertHistoryPanelClose) {
            this.alertHistoryPanelClose.addEventListener('click', () => this.hideAlertPanel('history'));
        }
        if (this.alertDetailPanelClose) {
            this.alertDetailPanelClose.addEventListener('click', () => this.hideAlertPanel('detail'));
        }

        // 主题切换
        if (this.themeToggleBtn) {
            this.themeToggleBtn.addEventListener('click', () => this.toggleTheme());
        }

        // 点击面板外部关闭
        document.addEventListener('click', (e) => {
            if (this.alertHistoryPanel && this.alertHistoryPanel.style.display === 'block') {
                if (!e.target.closest('.alert-panel') && !e.target.closest('#alertHistoryBtn')) {
                    this.hideAlertPanel('history');
                }
            }
            if (this.alertDetailPanel && this.alertDetailPanel.style.display === 'block') {
                if (!e.target.closest('.alert-detail-panel')) {
                    this.hideAlertPanel('detail');
                }
            }
        });
    }

    // ==================== 侧边栏 (移动端) ====================

    setupMobileSidebar() {
        this.mobileMenuBtn = document.createElement('button');
        this.mobileMenuBtn.className = 'mobile-menu-btn';
        this.mobileMenuBtn.innerHTML = `
            <svg width="20" height="20" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
                <path d="M3 6H21M3 12H21M3 18H21" stroke="currentColor" stroke-width="2" stroke-linecap="round"/>
            </svg>
        `;
        this.mobileMenuBtn.setAttribute('aria-label', '菜单');
        document.body.appendChild(this.mobileMenuBtn);

        this.mobileMenuBtn.addEventListener('click', () => this.toggleMobileSidebar(true));
        if (this.sidebarBackdrop) {
            this.sidebarBackdrop.addEventListener('click', () => this.toggleMobileSidebar(false));
        }
    }

    toggleMobileSidebar(open) {
        if (!this.sidebar || !this.sidebarBackdrop) return;
        if (window.innerWidth > 768) return;
        this.sidebar.classList.toggle('open', open);
        this.sidebarBackdrop.classList.toggle('show', open);
    }

    // ==================== 暗黑模式 ====================

    initTheme() {
        const savedTheme = localStorage.getItem('theme');
        if (savedTheme === 'dark') {
            document.documentElement.setAttribute('data-theme', 'dark');
            this.updateThemeIcons(true);
        } else if (savedTheme === 'light') {
            document.documentElement.setAttribute('data-theme', 'light');
            this.updateThemeIcons(false);
        } else {
            // 跟随系统
            document.documentElement.removeAttribute('data-theme');
            const isDark = window.matchMedia('(prefers-color-scheme: dark)').matches;
            this.updateThemeIcons(isDark);
        }
        this.updateThemeColor();
    }

    toggleTheme() {
        const currentTheme = document.documentElement.getAttribute('data-theme');
        let isDark;
        if (!currentTheme) {
            // 当前跟随系统，切换到 explicit 模式
            const systemDark = window.matchMedia('(prefers-color-scheme: dark)').matches;
            if (systemDark) {
                document.documentElement.setAttribute('data-theme', 'light');
                localStorage.setItem('theme', 'light');
                isDark = false;
            } else {
                document.documentElement.setAttribute('data-theme', 'dark');
                localStorage.setItem('theme', 'dark');
                isDark = true;
            }
        } else if (currentTheme === 'dark') {
            document.documentElement.setAttribute('data-theme', 'light');
            localStorage.setItem('theme', 'light');
            isDark = false;
        } else {
            // Explicit light → 跟随系统
            document.documentElement.removeAttribute('data-theme');
            localStorage.removeItem('theme');
            isDark = window.matchMedia('(prefers-color-scheme: dark)').matches;
        }
        this.updateThemeIcons(isDark);
        this.updateThemeColor();
        this.updateHljsTheme(isDark);
    }

    updateThemeIcons(isDark) {
        if (this.themeIconSun && this.themeIconMoon) {
            this.themeIconSun.style.display = isDark ? 'block' : 'none';
            this.themeIconMoon.style.display = isDark ? 'none' : 'block';
        }
    }

    updateThemeColor() {
        const meta = document.getElementById('themeColorMeta');
        if (meta) {
            const isDark = document.documentElement.getAttribute('data-theme') === 'dark' ||
                (!document.documentElement.getAttribute('data-theme') &&
                 window.matchMedia('(prefers-color-scheme: dark)').matches);
            meta.setAttribute('content', isDark ? '#0B1120' : '#1E3A5F');
        }
    }

    updateHljsTheme(isDark) {
        const hljsLink = document.getElementById('hljsTheme');
        if (hljsLink) {
            hljsLink.href = isDark
                ? 'https://cdn.jsdelivr.net/npm/highlight.js@11.9.0/styles/github-dark.min.css'
                : 'https://cdn.jsdelivr.net/npm/highlight.js@11.9.0/styles/github.min.css';
        }
    }

    // ==================== 工具菜单 ====================

    toggleToolsMenu() {
        if (this.toolsMenu && this.toolsBtn) {
            const wrapper = this.toolsBtn.closest('.tools-btn-wrapper');
            if (wrapper) wrapper.classList.toggle('active');
        }
    }

    closeToolsMenu() {
        if (this.toolsMenu && this.toolsBtn) {
            const wrapper = this.toolsBtn.closest('.tools-btn-wrapper');
            if (wrapper) wrapper.classList.remove('active');
        }
    }

    // ==================== 对话管理 ====================

    newChat() {
        if (this.isStreaming) {
            this.showNotification('请等待当前对话完成后再新建对话', 'warning');
            return;
        }

        // 如果当前有对话内容，保存
        if (this.currentChatHistory.length > 0) {
            if (this.isCurrentChatFromHistory) {
                this.updateCurrentChatHistory();
            } else {
                this.saveCurrentChat();
            }
        }

        this.isStreaming = false;
        if (this.messageInput) this.messageInput.value = '';
        this.currentChatHistory = [];
        this.isCurrentChatFromHistory = false;
        if (this.chatMessages) this.chatMessages.innerHTML = '';
        this.sessionId = this.generateSessionId();
        this.currentMode = 'quick';
        this.updateUI();
        this.checkAndSetCentered();
        this.renderChatHistory();
    }

    saveCurrentChat() {
        if (this.currentChatHistory.length === 0) return;

        const existingIndex = this.chatHistories.findIndex(h => h.id === this.sessionId);
        if (existingIndex !== -1) {
            this.updateCurrentChatHistory();
            return;
        }

        const firstUserMessage = this.currentChatHistory.find(msg => msg.type === 'user');
        const title = firstUserMessage
            ? (firstUserMessage.content.substring(0, 30) + (firstUserMessage.content.length > 30 ? '...' : ''))
            : '新对话';

        this.chatHistories.unshift({
            id: this.sessionId,
            title: title,
            messages: [...this.currentChatHistory],
            createdAt: new Date().toISOString(),
            updatedAt: new Date().toISOString()
        });

        if (this.chatHistories.length > 50) {
            this.chatHistories = this.chatHistories.slice(0, 50);
        }
        this.saveChatHistories();
    }

    updateCurrentChatHistory() {
        if (this.currentChatHistory.length === 0) return;

        const existingIndex = this.chatHistories.findIndex(h => h.id === this.sessionId);
        if (existingIndex === -1) {
            this.saveCurrentChat();
            return;
        }

        const history = this.chatHistories[existingIndex];
        history.messages = [...this.currentChatHistory];
        history.updatedAt = new Date().toISOString();

        const firstUserMessage = this.currentChatHistory.find(msg => msg.type === 'user');
        if (firstUserMessage) {
            const newTitle = firstUserMessage.content.substring(0, 30) +
                (firstUserMessage.content.length > 30 ? '...' : '');
            if (history.title !== newTitle) history.title = newTitle;
        }
        this.saveChatHistories();
    }

    loadChatHistories() {
        try {
            const stored = localStorage.getItem('chatHistories');
            return stored ? JSON.parse(stored) : [];
        } catch (e) {
            return [];
        }
    }

    saveChatHistories() {
        try {
            localStorage.setItem('chatHistories', JSON.stringify(this.chatHistories));
        } catch (e) {
            console.error('保存历史对话失败:', e);
        }
    }

    renderChatHistory() {
        if (!this.chatHistoryList) return;
        this.chatHistoryList.innerHTML = '';
        if (this.chatHistories.length === 0) return;

        this.chatHistories.forEach((history) => {
            const historyItem = document.createElement('div');
            historyItem.className = 'history-item';
            historyItem.dataset.historyId = history.id;
            historyItem.innerHTML = `
                <div class="history-item-content">
                    <span class="history-item-title">${this.escapeHtml(history.title)}</span>
                </div>
                <button class="history-item-delete" data-history-id="${history.id}" title="删除">
                    <svg viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
                        <path d="M18 6L6 18M6 6L18 18" stroke="currentColor" stroke-width="2" stroke-linecap="round"/>
                    </svg>
                </button>
            `;

            historyItem.addEventListener('click', (e) => {
                if (!e.target.closest('.history-item-delete')) {
                    this.loadChatHistory(history.id);
                    this.toggleMobileSidebar(false);
                }
            });

            const deleteBtn = historyItem.querySelector('.history-item-delete');
            deleteBtn.addEventListener('click', (e) => {
                e.stopPropagation();
                this.deleteChatHistory(history.id);
            });

            this.chatHistoryList.appendChild(historyItem);
        });
    }

    loadChatHistory(historyId) {
        const history = this.chatHistories.find(h => h.id === historyId);
        if (!history) return;

        if (this.currentChatHistory.length > 0 && this.sessionId !== historyId) {
            if (this.isCurrentChatFromHistory) {
                this.updateCurrentChatHistory();
            } else {
                this.saveCurrentChat();
            }
        }

        this.sessionId = history.id;
        this.currentChatHistory = [...history.messages];
        this.isCurrentChatFromHistory = true;

        if (this.chatMessages) {
            this.chatMessages.innerHTML = '';
            history.messages.forEach(msg => {
                this.addMessage(msg.type, msg.content, false, false);
            });
        }
        this.checkAndSetCentered();
        this.renderChatHistory();
    }

    deleteChatHistory(historyId) {
        this.chatHistories = this.chatHistories.filter(h => h.id !== historyId);
        this.saveChatHistories();
        this.renderChatHistory();

        if (this.sessionId === historyId) {
            this.currentChatHistory = [];
            if (this.chatMessages) this.chatMessages.innerHTML = '';
            this.sessionId = this.generateSessionId();
            this.checkAndSetCentered();
        }
    }

    // ==================== 模式切换 ====================

    toggleModeDropdown() {
        if (this.modeSelectorBtn && this.modeDropdown) {
            const wrapper = this.modeSelectorBtn.closest('.mode-selector-wrapper');
            if (wrapper) wrapper.classList.toggle('active');
        }
    }

    closeModeDropdown() {
        if (this.modeSelectorBtn && this.modeDropdown) {
            const wrapper = this.modeSelectorBtn.closest('.mode-selector-wrapper');
            if (wrapper) wrapper.classList.remove('active');
        }
    }

    selectMode(mode) {
        if (this.isStreaming) {
            this.showNotification('请等待当前对话完成后再切换模式', 'warning');
            return;
        }
        this.currentMode = mode;
        this.updateUI();
        const modeNames = { 'quick': '快速', 'stream': '流式' };
        this.showNotification(`已切换到${modeNames[mode]}模式`, 'info');
    }

    updateUI() {
        if (this.currentModeText) {
            const modeNames = { 'quick': '快速', 'stream': '流式' };
            this.currentModeText.textContent = modeNames[this.currentMode] || '快速';
        }

        document.querySelectorAll('.dropdown-item').forEach(item => {
            const mode = item.getAttribute('data-mode');
            item.classList.toggle('active', mode === this.currentMode);
        });

        if (this.sendButton) this.sendButton.disabled = this.isStreaming;
        if (this.messageInput) {
            this.messageInput.disabled = this.isStreaming;
        }
    }

    generateSessionId() {
        return 'session_' + Math.random().toString(36).substr(2, 9) + '_' + Date.now();
    }

    // ==================== 消息发送 ====================

    async sendMessage() {
        let message = '';
        if (this.messageInput) {
            message = this.messageInput.value.trim();
        }
        if (!message) {
            this.showNotification('请输入消息内容', 'warning');
            return;
        }
        if (this.isStreaming) {
            this.showNotification('请等待当前对话完成', 'warning');
            return;
        }

        this.addMessage('user', message);
        if (this.messageInput) {
            this.messageInput.value = '';
            this.autoResizeInput();
        }

        this.isStreaming = true;
        this.updateUI();

        try {
            if (this.currentMode === 'quick') {
                await this.sendQuickMessage(message);
            } else if (this.currentMode === 'stream') {
                await this.sendStreamMessage(message);
            }
        } catch (error) {
            console.error('发送消息失败:', error);
            this.addMessage('assistant', '抱歉，发送消息时出现错误：' + error.message);
        } finally {
            this.isStreaming = false;
            this.updateUI();
            if (this.isCurrentChatFromHistory && this.currentChatHistory.length > 0) {
                this.updateCurrentChatHistory();
                this.renderChatHistory();
            }
        }
    }

    async sendQuickMessage(message) {
        const loadingMessage = this.addLoadingMessage('正在思考...');

        try {
            const response = await fetch(`${this.apiBaseUrl}/chat`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ Id: this.sessionId, Question: message })
            });

            if (!response.ok) throw new Error(`HTTP错误: ${response.status}`);

            const data = await response.json();

            if (loadingMessage && loadingMessage.parentNode) {
                loadingMessage.parentNode.removeChild(loadingMessage);
            }

            if (data.code === 200 || data.message === 'success') {
                const chatResponse = data.data;
                if (chatResponse && chatResponse.success) {
                    this.addMessage('assistant', chatResponse.answer || '（无回复内容）');
                } else if (chatResponse && chatResponse.errorMessage) {
                    throw new Error(chatResponse.errorMessage);
                } else {
                    this.addMessage('assistant', chatResponse?.answer || chatResponse?.errorMessage || '服务返回了空内容');
                }
            } else {
                throw new Error(data.message || '请求失败');
            }
        } catch (error) {
            if (loadingMessage && loadingMessage.parentNode) {
                loadingMessage.parentNode.removeChild(loadingMessage);
            }
            throw error;
        }
    }

    async sendStreamMessage(message) {
        try {
            const response = await fetch(`${this.apiBaseUrl}/chat_stream`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ Id: this.sessionId, Question: message })
            });

            if (!response.ok) throw new Error(`HTTP错误: ${response.status}`);

            const assistantMessageElement = this.addMessage('assistant', '', true);
            let fullResponse = '';

            const reader = response.body.getReader();
            const decoder = new TextDecoder();
            let buffer = '';
            let currentEvent = '';

            try {
                while (true) {
                    const { done, value } = await reader.read();

                    if (done) {
                        this.handleStreamComplete(assistantMessageElement, fullResponse);
                        break;
                    }

                    buffer += decoder.decode(value, { stream: true });
                    const lines = buffer.split('\n');
                    buffer = lines.pop() || '';

                    for (const line of lines) {
                        if (line.trim() === '') continue;

                        if (line.startsWith('id:')) continue;
                        if (line.startsWith('event:')) {
                            currentEvent = line.substring(6).trim();
                            continue;
                        }
                        if (!line.startsWith('data:')) continue;

                        const rawData = line.substring(5).trim();

                        if (rawData === '[DONE]') {
                            this.handleStreamComplete(assistantMessageElement, fullResponse);
                            return;
                        }

                        try {
                            const sseMessage = JSON.parse(rawData);
                            if (sseMessage && typeof sseMessage.type === 'string') {
                                if (sseMessage.type === 'content') {
                                    fullResponse += sseMessage.data || '';
                                    if (assistantMessageElement) {
                                        const messageContent = assistantMessageElement.querySelector('.message-content');
                                        messageContent.innerHTML = this.renderMarkdown(fullResponse);
                                        this.highlightCodeBlocks(messageContent);
                                        this.scrollToBottom();
                                    }
                                } else if (sseMessage.type === 'done') {
                                    this.handleStreamComplete(assistantMessageElement, fullResponse);
                                    return;
                                } else if (sseMessage.type === 'error') {
                                    if (assistantMessageElement) {
                                        const messageContent = assistantMessageElement.querySelector('.message-content');
                                        messageContent.innerHTML = this.renderMarkdown('错误: ' + (sseMessage.data || '未知错误'));
                                    }
                                    return;
                                }
                            } else {
                                fullResponse += rawData;
                                if (assistantMessageElement) {
                                    const messageContent = assistantMessageElement.querySelector('.message-content');
                                    messageContent.innerHTML = this.renderMarkdown(fullResponse);
                                    this.highlightCodeBlocks(messageContent);
                                    this.scrollToBottom();
                                }
                            }
                        } catch (e) {
                            fullResponse += rawData;
                            if (assistantMessageElement) {
                                const messageContent = assistantMessageElement.querySelector('.message-content');
                                messageContent.innerHTML = this.renderMarkdown(fullResponse);
                                this.highlightCodeBlocks(messageContent);
                                this.scrollToBottom();
                            }
                        }
                    }
                }
            } finally {
                reader.releaseLock();
            }
        } catch (error) {
            throw error;
        }
    }

    // ==================== 消息渲染 ====================

    addMessage(type, content, isStreaming = false, saveToHistory = true) {
        const isFirstMessage = this.chatMessages &&
            this.chatMessages.querySelectorAll('.message').length === 0;

        if (!isStreaming && saveToHistory && content) {
            this.currentChatHistory.push({
                type: type,
                content: content,
                timestamp: new Date().toISOString()
            });
        }

        const messageDiv = document.createElement('div');
        messageDiv.className = `message ${type}${isStreaming ? ' streaming' : ''}`;

        if (type === 'assistant') {
            const messageAvatar = document.createElement('div');
            messageAvatar.className = 'message-avatar';
            messageAvatar.innerHTML = `
                <svg width="20" height="20" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
                    <path d="M12 2L15.09 8.26L22 9.27L17 14.14L18.18 21.02L12 17.77L5.82 21.02L7 14.14L2 9.27L8.91 8.26L12 2Z" fill="white"/>
                </svg>
            `;
            messageDiv.appendChild(messageAvatar);
        }

        const messageContentWrapper = document.createElement('div');
        messageContentWrapper.className = 'message-content-wrapper';

        const messageContent = document.createElement('div');
        messageContent.className = 'message-content';

        if (type === 'assistant' && !isStreaming) {
            messageContent.innerHTML = this.renderMarkdown(content);
            this.highlightCodeBlocks(messageContent);
        } else {
            messageContent.textContent = content;
        }

        messageContentWrapper.appendChild(messageContent);
        messageDiv.appendChild(messageContentWrapper);

        if (this.chatMessages) {
            this.chatMessages.appendChild(messageDiv);
            if (isFirstMessage && this.chatContainer) {
                this.chatContainer.classList.remove('centered');
            }
            this.scrollToBottom();
        }

        return messageDiv;
    }

    addLoadingMessage(content) {
        const messageDiv = document.createElement('div');
        messageDiv.className = 'message assistant';

        const messageAvatar = document.createElement('div');
        messageAvatar.className = 'message-avatar';
        messageAvatar.innerHTML = `
            <svg width="20" height="20" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
                <path d="M12 2L15.09 8.26L22 9.27L17 14.14L18.18 21.02L12 17.77L5.82 21.02L7 14.14L2 9.27L8.91 8.26L12 2Z" fill="white"/>
            </svg>
        `;
        messageDiv.appendChild(messageAvatar);

        const messageContentWrapper = document.createElement('div');
        messageContentWrapper.className = 'message-content-wrapper';

        const messageContent = document.createElement('div');
        messageContent.className = 'message-content loading-message-content';
        messageContent.innerHTML = `
            <span>${this.escapeHtml(content)}</span>
            <span class="loading-spinner-icon">
                <svg width="16" height="16" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
                    <path d="M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm0 18c-4.41 0-8-3.59-8-8s3.59-8 8-8 8 3.59 8 8-3.59 8-8 8z" fill="currentColor" opacity="0.2"/>
                    <path d="M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10c1.54 0 3-.36 4.28-1l-1.5-2.6C13.64 19.62 12.84 20 12 20c-4.41 0-8-3.59-8-8s3.59-8 8-8c.84 0 1.64.38 2.18 1l1.5-2.6C13 2.36 12.54 2 12 2z" fill="currentColor"/>
                </svg>
            </span>
        `;

        messageContentWrapper.appendChild(messageContent);
        messageDiv.appendChild(messageContentWrapper);

        if (this.chatMessages) {
            this.chatMessages.appendChild(messageDiv);
            const isFirstMessage = this.chatMessages.querySelectorAll('.message').length === 1;
            if (isFirstMessage && this.chatContainer) {
                this.chatContainer.classList.remove('centered');
            }
            this.scrollToBottom();
        }

        return messageDiv;
    }

    checkAndSetCentered() {
        if (this.chatMessages && this.chatContainer) {
            const hasMessages = this.chatMessages.querySelectorAll('.message').length > 0;
            this.chatContainer.classList.toggle('centered', !hasMessages);
        }
    }

    scrollToBottom() {
        if (this.chatMessages) {
            this.chatMessages.scrollTop = this.chatMessages.scrollHeight;
        }
    }

    handleStreamComplete(assistantMessageElement, fullResponse) {
        if (assistantMessageElement) {
            assistantMessageElement.classList.remove('streaming');
            const messageContent = assistantMessageElement.querySelector('.message-content');
            if (messageContent) {
                messageContent.innerHTML = this.renderMarkdown(fullResponse);
                this.highlightCodeBlocks(messageContent);
            }
        }
        if (fullResponse) {
            this.currentChatHistory.push({
                type: 'assistant',
                content: fullResponse,
                timestamp: new Date().toISOString()
            });
            if (this.isCurrentChatFromHistory) {
                this.updateCurrentChatHistory();
                this.renderChatHistory();
            }
        }
    }

    autoResizeInput() {
        if (this.messageInput) {
            this.messageInput.style.height = 'auto';
            this.messageInput.style.height = Math.min(this.messageInput.scrollHeight, 150) + 'px';
        }
    }

    // ==================== 通知 ====================

    showNotification(message, type = 'info') {
        const notification = document.createElement('div');
        notification.className = `notification ${type}`;
        notification.textContent = message;

        const colors = {
            info: 'var(--color-info)',
            success: 'var(--color-success)',
            warning: 'var(--color-warning)',
            error: 'var(--color-error)'
        };
        notification.style.backgroundColor = colors[type] || colors.info;

        document.body.appendChild(notification);
        setTimeout(() => {
            notification.style.animation = 'slideOut 0.3s ease';
            setTimeout(() => {
                if (notification.parentNode) {
                    notification.parentNode.removeChild(notification);
                }
            }, 300);
        }, 3000);
    }

    // ==================== 文件上传 ====================

    handleFileSelect(event) {
        const file = event.target.files[0];
        if (file) {
            if (!this.validateFileType(file)) {
                this.showNotification('只支持上传 TXT 或 Markdown (.md) 格式的文件', 'error');
                this.fileInput.value = '';
                return;
            }
            this.uploadFile(file);
        }
    }

    validateFileType(file) {
        const fileName = file.name.toLowerCase();
        return ['.txt', '.md', '.markdown'].some(ext => fileName.endsWith(ext));
    }

    async uploadFile(file) {
        if (!this.validateFileType(file)) {
            this.showNotification('只支持上传 TXT 或 Markdown (.md) 格式的文件', 'error');
            return;
        }

        const maxSize = 50 * 1024 * 1024;
        if (file.size > maxSize) {
            this.showNotification('文件大小不能超过50MB', 'error');
            return;
        }

        this.isStreaming = true;
        this.updateUI();
        this.showOverlay(true, '正在上传文件...', `上传: ${file.name}`);

        try {
            const formData = new FormData();
            formData.append('file', file);

            const response = await fetch(`${this.apiBaseUrl}/upload`, {
                method: 'POST',
                body: formData
            });

            if (!response.ok) throw new Error(`HTTP错误: ${response.status}`);

            const data = await response.json();

            if ((data.code === 200 || data.message === 'success') && data.data) {
                this.addMessage('assistant', `${file.name} 上传到知识库成功`, false, true);
            } else {
                throw new Error(data.message || '上传失败');
            }
        } catch (error) {
            console.error('文件上传失败:', error);
            this.showNotification('文件上传失败: ' + error.message, 'error');
        } finally {
            if (this.fileInput) this.fileInput.value = '';
            this.isStreaming = false;
            this.showOverlay(false);
            this.updateUI();
        }
    }

    formatFileSize(bytes) {
        if (bytes === 0) return '0 Bytes';
        const k = 1024;
        const sizes = ['Bytes', 'KB', 'MB', 'GB'];
        const i = Math.floor(Math.log(bytes) / Math.log(k));
        return Math.round(bytes / Math.pow(k, i) * 100) / 100 + ' ' + sizes[i];
    }

    // ==================== 加载遮罩 ====================

    showOverlay(show, text, subtext) {
        if (!this.loadingOverlay) return;
        if (show) {
            this.loadingOverlay.classList.add('show');
            if (this.loadingText && text) this.loadingText.textContent = text;
            if (this.loadingSubtext && subtext) this.loadingSubtext.textContent = subtext;
            document.body.style.overflow = 'hidden';
        } else {
            this.loadingOverlay.classList.remove('show');
            document.body.style.overflow = '';
        }
    }

    // ==================== AI Ops ====================

    parseSSEJson(data) {
        // 支持单JSON对象和多个JSON对象（拼接在同一行）
        const results = [];
        const jsonPattern = /\{"type"\s*:\s*"[^"]+"\s*,\s*"data"\s*:\s*(?:"[^"]*"|null)\}/g;
        const matches = data.match(jsonPattern);

        if (matches && matches.length > 0) {
            for (const jsonStr of matches) {
                try {
                    results.push(JSON.parse(jsonStr));
                } catch (e) {
                    // 跳过解析失败的
                }
            }
        } else {
            try {
                results.push(JSON.parse(data));
            } catch (e) {
                return null; // 非JSON格式
            }
        }
        return results.length > 0 ? results : null;
    }

    async sendAIOpsRequest(loadingMessageElement, alertContext, alertId) {
        const body = (alertContext || alertId)
            ? JSON.stringify({ alertContext: alertContext || '', alertId: alertId || '' })
            : '{}';

        try {
            const response = await fetch(`${this.apiBaseUrl}/ai_ops`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: body
            });

            if (!response.ok) throw new Error(`HTTP错误: ${response.status}`);

            let fullResponse = '';
            const reader = response.body.getReader();
            const decoder = new TextDecoder();
            let buffer = '';

            try {
                while (true) {
                    const { done, value } = await reader.read();
                    if (done) {
                        if (fullResponse) {
                            this.updateAIOpsMessage(loadingMessageElement, fullResponse, []);
                        }
                        break;
                    }

                    buffer += decoder.decode(value, { stream: true });
                    const lines = buffer.split('\n');
                    buffer = lines.pop() || '';

                    for (const line of lines) {
                        if (line.trim() === '') continue;
                        if (line.startsWith('id:')) continue;
                        if (line.startsWith('event:')) continue;
                        if (!line.startsWith('data:')) continue;

                        const rawData = line.substring(5).trim();
                        const parsedMessages = this.parseSSEJson(rawData);

                        if (parsedMessages === null) {
                            // 非JSON格式，直接追加
                            fullResponse += rawData;
                            this.updateAIOpsStreamContent(loadingMessageElement, fullResponse);
                            continue;
                        }

                        let isDone = false;
                        for (const msg of parsedMessages) {
                            if (msg.type === 'content') {
                                fullResponse += msg.data || '';
                            } else if (msg.type === 'done') {
                                isDone = true;
                                this.updateAIOpsMessage(loadingMessageElement, fullResponse, []);
                            } else if (msg.type === 'error') {
                                throw new Error(msg.data || '智能运维分析失败');
                            }
                        }

                        if (!isDone && loadingMessageElement) {
                            this.updateAIOpsStreamContent(loadingMessageElement, fullResponse);
                        }
                        if (isDone) return;
                    }
                }
            } finally {
                reader.releaseLock();
            }
        } catch (error) {
            throw error;
        }
    }

    updateAIOpsStreamContent(messageElement, content) {
        if (!messageElement) return;
        messageElement.classList.add('aiops-message');

        const wrapper = messageElement.querySelector('.message-content-wrapper');
        if (!wrapper) return;

        let messageContent = wrapper.querySelector('.message-content');
        if (!messageContent) {
            messageContent = document.createElement('div');
            messageContent.className = 'message-content';
            wrapper.appendChild(messageContent);
        }
        messageContent.textContent = content;
        this.scrollToBottom();
    }

    updateAIOpsMessage(messageElement, response) {
        if (!messageElement) return;

        messageElement.classList.add('aiops-message');
        const wrapper = messageElement.querySelector('.message-content-wrapper');
        if (!wrapper) return;

        const messageContent = wrapper.querySelector('.message-content');
        if (!messageContent) return;

        messageContent.classList.remove('loading-message-content');
        // 移除加载图标
        const loadingIcon = messageContent.querySelector('.loading-spinner-icon');
        if (loadingIcon) loadingIcon.remove();
        // 保留文字节点，但移除旧的文字
        const textSpan = messageContent.querySelector('span');
        if (textSpan) textSpan.remove();

        // 渲染Markdown
        messageContent.innerHTML = this.renderMarkdown(response);
        this.highlightCodeBlocks(messageContent);

        this.currentChatHistory.push({
            type: 'assistant',
            content: response,
            timestamp: new Date().toISOString()
        });

        this.scrollToBottom();
        return messageElement;
    }

    // ==================== 告警系统 ====================

    connectAlertSSE() {
        // 关闭旧连接
        if (this.alertEventSource) {
            this.alertEventSource.close();
        }

        this.alertEventSource = new EventSource(`${this.apiBaseUrl}/alerts/stream`);

        this.alertEventSource.onmessage = (event) => {
            try {
                const data = JSON.parse(event.data);
                if (data.type === 'new_alert') {
                    this.showAlertNotification(data);
                }
            } catch (e) {
                console.error('解析告警SSE数据失败:', e);
            }
        };

        this.alertEventSource.onerror = () => {
            console.error('告警SSE连接断开，5秒后重试');
            if (this.alertEventSource) {
                this.alertEventSource.close();
                this.alertEventSource = null;
            }
            setTimeout(() => this.connectAlertSSE(), 5000);
        };
    }

    showAlertNotification(data) {
        this.pendingAlertId = data.alertId;

        // 移除旧通知
        const oldNotif = document.getElementById('_dynamic_alert_notif');
        if (oldNotif) oldNotif.remove();

        const isFiring = data.status === 'firing';
        const color = isFiring ? '#d93025' : '#f9ab00';
        const notif = document.createElement('div');
        notif.id = '_dynamic_alert_notif';
        notif.className = 'alert-notification';

        notif.innerHTML = `
            <div class="alert-notification-header" style="background:${color}">
                <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                    <path d="M18 8C18 4.68629 15.3137 2 12 2C8.68629 2 6 4.68629 6 8V12L4 15H20L18 12V8Z" stroke-linecap="round" stroke-linejoin="round"/>
                    <path d="M9 17V18C9 19.6569 10.3431 21 12 21C13.6569 21 15 19.6569 15 18V17" stroke-linecap="round" stroke-linejoin="round"/>
                </svg>
                <span class="alert-notification-title">新告警</span>
                <span class="alert-notification-close" id="_alert_notif_close">&times;</span>
            </div>
            <div class="alert-notification-body">
                ${this.escapeHtml(data.summary || '收到新的告警推送')}
            </div>
            <div class="alert-notification-footer">
                <button class="alert-notification-btn" id="_alert_notif_view">查看详情</button>
            </div>
        `;

        document.body.appendChild(notif);

        // 使用addEventListener代替内联onclick
        const closeBtn = notif.querySelector('#_alert_notif_close');
        if (closeBtn) {
            closeBtn.addEventListener('click', () => notif.remove());
        }

        const viewBtn = notif.querySelector('#_alert_notif_view');
        if (viewBtn) {
            viewBtn.addEventListener('click', () => {
                notif.remove();
                if (this.pendingAlertId) {
                    this.showAlertDetail(this.pendingAlertId);
                }
            });
        }

        // 10秒后自动移除
        setTimeout(() => {
            const el = document.getElementById('_dynamic_alert_notif');
            if (el) el.remove();
        }, 10000);
    }

    hideAlertPanel(type) {
        const panel = type === 'history' ? this.alertHistoryPanel : this.alertDetailPanel;
        if (panel) {
            panel.style.display = 'none';
            panel.classList.remove('open');
        }
    }

    async showAlertHistory() {
        if (!this.alertHistoryPanel || !this.alertHistoryContent) return;

        this.alertHistoryPanel.style.display = 'block';
        this.alertHistoryPanel.classList.add('open');
        this.alertHistoryContent.innerHTML = '<div class="alert-panel-loading">加载中...</div>';

        try {
            const response = await fetch(`${this.apiBaseUrl}/alerts/history`);
            if (!response.ok) throw new Error(`HTTP错误: ${response.status}`);

            const data = await response.json();
            this.renderAlertHistory(data.alerts || []);
        } catch (error) {
            console.error('获取告警历史失败:', error);
            this.alertHistoryContent.innerHTML =
                '<div class="alert-panel-error">获取告警历史失败: ' + this.escapeHtml(error.message) + '</div>';
        }
    }

    renderAlertHistory(alerts) {
        if (!this.alertHistoryContent) return;

        if (!alerts || alerts.length === 0) {
            this.alertHistoryContent.innerHTML = '<div class="alert-panel-empty">暂无告警记录</div>';
            return;
        }

        let html = '';
        alerts.forEach(alert => {
            const severityClass = alert.severity === 'critical' ? 'severity-critical' : 'severity-warning';
            const time = new Date(alert.receivedAt).toLocaleString('zh-CN');
            const statusText = alert.status === 'firing' ? '触发中' : '已恢复';
            const statusClass = alert.status === 'firing' ? 'status-firing' : 'status-resolved';

            html += `
                <div class="alert-card" data-alert-id="${this.escapeHtml(alert.id)}">
                    <div class="alert-card-header">
                        <span class="alert-severity ${severityClass}">${this.escapeHtml(alert.severity)}</span>
                        <span class="alert-status ${statusClass}">${statusText}</span>
                        <span class="alert-time">${time}</span>
                    </div>
                    <div class="alert-card-body">${this.escapeHtml(alert.summary || '未知告警')}</div>
                    <div class="alert-card-footer">
                        ${alert.hasReport ? '<span class="alert-has-report">已有报告</span>' : '<span class="alert-no-report">暂无报告</span>'}
                        <button class="alert-view-btn" data-alert-id="${this.escapeHtml(alert.id)}">查看详情</button>
                    </div>
                </div>
            `;
        });

        this.alertHistoryContent.innerHTML = '<div class="alert-card-list">' + html + '</div>';

        this.alertHistoryContent.querySelectorAll('.alert-view-btn').forEach(btn => {
            btn.addEventListener('click', (e) => {
                e.stopPropagation();
                const alertId = btn.getAttribute('data-alert-id');
                if (alertId) {
                    this.hideAlertPanel('history');
                    this.showAlertDetail(alertId);
                }
            });
        });
    }

    async showAlertDetail(alertId) {
        if (!this.alertDetailPanel || !this.alertDetailContent) return;

        this.alertDetailPanel.style.display = 'block';
        this.alertDetailPanel.classList.add('open');
        this.alertDetailContent.innerHTML = '<div class="alert-panel-loading">加载中...</div>';

        try {
            const detailResponse = await fetch(`${this.apiBaseUrl}/alerts/detail/${alertId}`);
            if (!detailResponse.ok) throw new Error(`获取告警详情失败: ${detailResponse.status}`);
            const detailData = await detailResponse.json();

            const reportResponse = await fetch(`${this.apiBaseUrl}/alerts/report/${alertId}`);
            let reportHtml = '';

            if (reportResponse.ok) {
                const reportData = await reportResponse.json();
                if (reportData && reportData.report) {
                    reportHtml = this.renderMarkdown(reportData.report);
                } else {
                    reportHtml = '<div class="alert-panel-empty">报告生成中或暂无可用的分析报告</div>';
                }
            } else {
                reportHtml = '<div class="alert-panel-empty">获取报告失败</div>';
            }

            const time = new Date(detailData.receivedAt).toLocaleString('zh-CN');
            let alertsHtml = '';
            let alertContext = '';

            if (detailData.alerts) {
                detailData.alerts.forEach(alert => {
                    const labels = alert.labels
                        ? Object.entries(alert.labels).map(([k, v]) => `${k}: ${v}`).join(', ') : '无';
                    const annotations = alert.annotations
                        ? Object.entries(alert.annotations).map(([k, v]) => `${k}: ${v}`).join(', ') : '无';
                    alertsHtml += `
                        <div class="alert-detail-item">
                            <div><strong>状态:</strong> ${alert.status}</div>
                            <div><strong>开始时间:</strong> ${alert.startsAt || '未知'}</div>
                            <div><strong>标签:</strong> ${this.escapeHtml(labels)}</div>
                            <div><strong>注解:</strong> ${this.escapeHtml(annotations)}</div>
                        </div>
                    `;

                    const name = alert.labels ? (alert.labels.alertname || '未知告警') : '未知告警';
                    alertContext += '告警: ' + name + '\n状态: ' + (alert.status || 'unknown') + '\n';
                    if (alert.labels) {
                        Object.entries(alert.labels).forEach(([k, v]) => { alertContext += '  ' + k + ': ' + v + '\n'; });
                    }
                    if (alert.annotations) {
                        Object.entries(alert.annotations).forEach(([k, v]) => { alertContext += '  ' + k + ': ' + v + '\n'; });
                    }
                });
            }

            this.alertDetailContent.innerHTML = `
                <div class="alert-detail-section">
                    <div class="alert-detail-info">
                        <div><strong>告警ID:</strong> ${this.escapeHtml(detailData.id)}</div>
                        <div><strong>状态:</strong> ${detailData.status === 'firing' ? '触发中' : '已恢复'}</div>
                        <div><strong>接收时间:</strong> ${time}</div>
                    </div>
                </div>
                <div class="alert-detail-section">
                    <h4>告警列表</h4>
                    ${alertsHtml || '<div class="alert-panel-empty">无告警数据</div>'}
                </div>
                <div class="alert-detail-section">
                    <h4>分析报告</h4>
                    <div class="alert-report-content">${reportHtml}</div>
                    <button class="alert-aiops-btn" data-alert-id="${this.escapeHtml(alertId)}">对此告警执行 AI Ops 分析</button>
                </div>
            `;

            const aiOpsBtn = this.alertDetailContent.querySelector('.alert-aiops-btn');
            if (aiOpsBtn) {
                aiOpsBtn.addEventListener('click', () => {
                    this.hideAlertPanel('detail');
                    this.triggerAIOps(alertId, alertContext);
                });
            }

        } catch (error) {
            console.error('获取告警详情失败:', error);
            this.alertDetailContent.innerHTML =
                '<div class="alert-panel-error">获取告警详情失败: ' + this.escapeHtml(error.message) + '</div>';
        }
    }

    async simulateAlert() {
        try {
            const response = await fetch(`${this.apiBaseUrl}/alerts/simulate`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: '{}'
            });

            if (!response.ok) throw new Error(`HTTP错误: ${response.status}`);

            const data = await response.json();
            if (!data.success) {
                throw new Error(data.message || '模拟告警失败');
            }
        } catch (error) {
            console.error('模拟告警失败:', error);
            this.showNotification('模拟告警失败: ' + error.message, 'error');
        }
    }

    async triggerAIOps(alertId, alertContext) {
        if (this.isStreaming) {
            this.showNotification('请等待当前操作完成', 'warning');
            return;
        }

        this.newChat();
        this.isStreaming = true;
        this.updateUI();

        const loadingMessage = this.addLoadingMessage('分析中...');

        try {
            await this.sendAIOpsRequest(loadingMessage, alertContext, alertId);
        } catch (error) {
            console.error('智能运维分析失败:', error);
            if (loadingMessage) {
                const messageContent = loadingMessage.querySelector('.message-content');
                if (messageContent) {
                    messageContent.textContent = '抱歉，智能运维分析时出现错误：' + error.message;
                }
            }
        } finally {
            this.isStreaming = false;
            this.updateUI();
        }
    }

    // ==================== 工具 ====================

    escapeHtml(text) {
        const div = document.createElement('div');
        div.textContent = text;
        return div.innerHTML;
    }
}

// 初始化应用
document.addEventListener('DOMContentLoaded', () => {
    window.__app = new SuperBizAgentApp();
});
