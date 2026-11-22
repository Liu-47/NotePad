# NotePad

基于 Android 官方早期 NotePad 示例（ContentProvider + SQLite 记事本）扩展而来的移动应用开发课程项目。

在保留原版「ContentProvider 提供数据、ListActivity 展示列表」架构的基础上，补充了分类管理、搜索、时间戳、排序、主题等常用功能，并在界面做了轻量美化。

## 功能

### 原版功能
- 笔记的增、删、改、查
- 标题编辑、正文编辑，编辑页带横线稿纸效果
- 撤销（Revert）、保存（Save）
- 通过 `ContentProvider` 对外暴露笔记数据，支持 `LiveFolder`

### 扩展功能
| 功能 | 说明 |
|---|---|
| **分类管理** | 5 类（默认/工作/生活/学习/重要），列表项左侧彩色竖条 + 徽章标识，支持按分类筛选 |
| **搜索** | 标题与正文模糊匹配，输入即时过滤，使用 `?` 占位符防止 SQL 注入 |
| **友好时间戳** | 今天显示 `14:30`，昨天显示 `昨天 14:30`，本年显示 `3月5日 14:30`，跨年显示 `2025年3月5日` |
| **列表排序** | 修改时间 / 创建时间 × 升序 / 降序，共 4 种，切换主题或旋转屏幕后保持 |
| **主题切换** | 4 套配色（浅色蓝 / 纸黄 / 深色 / 浅色绿），通过 SharedPreferences 持久化 |
| **长按操作菜单** | 打开 / 设置分类 / 复制 / 删除 |
| **自动标题** | 正文首行自动提取为标题，空白折叠、超长截断 |

## 技术要点

- **数据层**：`SQLiteOpenHelper` + `UriMatcher` + `SQLiteQueryBuilder` 实现 `ContentProvider`
- **数据库升级**：`DATABASE_VERSION` 2 → 3，`onUpgrade` 中用 `ALTER TABLE ADD COLUMN` 原地升级，保留用户已有笔记
- **列表绑定**：`SimpleCursorAdapter` + `ViewBinder` 自定义字段到视图的映射
- **查询方式**：`ContentResolver.query()` + `changeCursor()`，替换已废弃的 `managedQuery()`
- **状态保持**：`onSaveInstanceState` 保存排序/筛选/搜索词，在视图绑定前恢复
- **主题系统**：`ThemeHelper` 统一输出调色板，Activity 重建时同步状态栏与 ActionBar 染色

## 环境

| 项 | 版本 |
|---|---|
| 语言 | Java |
| AGP | 9.3.0 |
| Gradle | 9.5.0 |
| compileSdk / targetSdk | 37 |
| minSdk | 23 |
| JDK | 17+ |

依赖仓库配置了阿里云镜像，国内网络可直接拉取。

## 运行

**Android Studio**：直接打开项目根目录，Gradle 同步完成后点击 Run。

> 注意：`Settings → Build, Execution, Deployment → Build Tools → Gradle → Gradle JDK` 必须选择 **JDK 17 或更高**。

**命令行**：

```bash
export JAVA_HOME="<你的 JDK 17+ 路径>"
./gradlew :app:assembleDebug
```

产物位于 `app/build/outputs/apk/debug/app-debug.apk`。

## 项目结构

```
app/src/main/java/com/example/android/notepad/
├── NotePad.java            # 常量定义：URI、列名、排序规则
├── NotePadProvider.java    # ContentProvider 实现
├── NotesList.java          # 笔记列表（筛选/搜索/排序/主题）
├── NoteEditor.java         # 笔记编辑（分类/自动标题）
├── TitleEditor.java        # 标题编辑器
├── NotesLiveFolder.java    # LiveFolder 支持
├── CategoryHelper.java     # 分类名称与配色
├── ThemeHelper.java        # 主题调色板
└── TimeUtils.java          # 友好时间格式化
```

## 说明

本项目基于 AOSP 的 NotePad 示例代码，原作者版权归 Android Open Source Project 所有，遵循 Apache License 2.0。扩展部分为课程实践所写。
