# Literal Memo

写。丢。搜。

基于 Git 同步的极简文本笔记应用。

<p>
  <a href="https://github.com/256x/memo/releases/latest"><img src="https://img.shields.io/github/v/release/256x/memo?label=GitHub%20Release"></a>&nbsp;<img src="https://img.shields.io/badge/Android-8%2B-blue">&nbsp;<img src="https://img.shields.io/badge/license-MIT-lightgrey">
</p>

<p>
<img width="180" alt="列表界面" src="https://github.com/user-attachments/assets/fe8a258c-2626-4b06-9321-8abf9a828a0e" />
<img width="180" alt="预览界面" src="https://github.com/user-attachments/assets/16d7dde8-5da1-4fac-9e64-542087cc41e2" />
<img width="180" alt="编辑界面" src="https://github.com/user-attachments/assets/7cc5ac37-548f-4e9d-809d-e2d64b4a83ff" />
<img width="180" alt="设置界面" src="https://github.com/user-attachments/assets/b6dd2944-d276-4d3a-9377-aac28ea56b48" />
</p>

[用户指南](./docs/USER_GUIDE.md)

## 为什么？

你是否曾在 Keep 里翻看那些尘封的笔记？

你是否曾犹豫，不敢把私密的思绪写进云服务？

但你仍然希望笔记能随处可用。

你的照片已经同步了。为什么笔记不行——而且由你掌控？

如果笔记根本不需要整理呢？

如果你只管写，需要的时候搜一下就好呢？

## 核心理念

Literal Memo 建立在一个简单的原则上：

**只管写。丢进去。需要时搜索。不需要就删掉。**

没有文件夹。没有标签。没有归档。没有恢复。

只有文字。

## 功能特点

- **写**：极简 Markdown 编辑器，带工具栏
- **搜**：所有笔记全文搜索
- **分享**：从其他应用接收链接和文本
- **同步**：Git 同步（GitHub），启动和编辑后自动同步
- **定制**：字体、颜色、左手操作模式

## 工作原理

笔记存储在简单的目录结构中：

```
repo/
├── pile/    ← 活跃笔记
└── trash/   ← 已删除笔记（仅 Git 同步，用于恢复）
```

没有复杂的状态管理。没有隐藏的元数据。只有文件。

## 为什么没有文件夹？

因为你根本不会用。

你不会去翻文件夹。你不会去翻归档。你只会搜索。

所以这就是这个应用优化的方向。

## 为什么没有恢复？

没有恢复按钮。没有撤销。

删除会立即从应用中移除笔记。如果开启了 Git 同步，文件会被移动到仓库中的 `trash/` 目录——不是永久删除，只是移出视线。

如果你真的需要找回什么，在仓库里把文件从 `trash/` 移回 `pile/`，然后同步即可。

这是有意为之。删除应该给人一种终结感。这样才能保持 pile 的整洁。

## 适合谁？

这个应用可能适合这样的人：

- 偏爱纯文本，而不是结构化系统
- 希望完全掌控自己的数据
- 用搜索代替导航
- 喜欢简单、可预测的工具
- 熟悉基于 Git 的工作流

## 哲学

Literal Memo 不是关于整理笔记。

而是关于**不需要整理**。

这个应用故意保持简单。如果一个功能需要分好几步来解释，那它可能不属于这里。

## 使用方法

### 分享链接

1. 在浏览器或任何应用中找到链接
2. 点击分享 → Literal Memo
3. 创建一条包含链接的新笔记
4. 稍后在预览模式中打开链接

### 多设备同步

Git 同步让你的笔记在多个设备之间保持同步：

- 启动应用和编辑后自动同步
- 不要在多台设备上同时编辑同一条笔记
- 如果发生冲突，先同步的设备胜出
- 所有变更都会保留在 Git 历史中

详细同步说明请参阅[用户指南](docs/USER_GUIDE.md)。

## 致谢

- **灵感来源于 [howm](https://kaoriya.github.io/howm/)** —— 一种建立在"先写，永远不整理"理念上的笔记工具。

## 开发

- Kotlin / Jetpack Compose
- 目标：Android 8.0+
- 无 Google API。无 Firebase。无追踪。

本应用的开发得到了 [Claude](https://claude.ai) (Anthropic) 的大量帮助。AI 参与了整个开发过程，包括编写代码。

## 许可

MIT

## PC 脚本

`scripts/` 目录提供了一些终端和编辑器使用的额外工具：

- `new-memo.sh` — 从终端创建新笔记
- `search-memo.sh` — 使用 fzf 搜索笔记
- `sync-memo.sh` — 与远程仓库同步 pile
- `literalmemo.vim` — Vim 集成（新建 / 列表 / 搜索）
- `literalmemo.lua` — 通过 fzf-lua 的 Neovim 集成

详见[用户指南](docs/USER_GUIDE.md)。
