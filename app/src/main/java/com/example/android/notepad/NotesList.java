/*
 * Copyright (C) 2007 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.android.notepad;

import com.example.android.notepad.NotePad;

import android.app.AlertDialog;
import android.app.ListActivity;
import android.content.ClipboardManager;
import android.content.ClipData;
import android.content.ComponentName;
import android.content.ContentUris;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.Log;
import android.util.TypedValue;
import android.view.ContextMenu;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ContextMenu.ContextMenuInfo;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.AdapterView;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.SimpleCursorAdapter;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Displays a list of notes. Will display notes from the {@link Uri}
 * provided in the incoming Intent if there is one, otherwise it defaults to displaying the
 * contents of the {@link NotePadProvider}.
 *
 * <p>【扩展说明】在原版基础上增加了四类能力，其余逻辑（菜单、上下文菜单、点击跳转、
 * 剪贴板粘贴、ALTERNATIVE 扩展项）保持原样：
 * <ol>
 *   <li>条目时间戳显示（读取 modified 列并用 {@link TimeUtils} 格式化）；</li>
 *   <li>笔记搜索——按标题/内容模糊查询，输入即时过滤；</li>
 *   <li>笔记排序——修改时间升降序、创建时间、标题字典序；</li>
 *   <li>笔记分类筛选 + 主题配色（配合 {@link ThemeHelper}、{@link CategoryHelper}）。</li>
 * </ol>
 *
 * NOTE: Notice that the provider operations in this Activity are taking place on the UI thread.
 * This is not a good practice. It is only done here to make the code more readable. A real
 * application should use the {@link android.content.AsyncQueryHandler} or
 * {@link android.os.AsyncTask} object to perform operations asynchronously on a separate thread.
 */
public class NotesList extends ListActivity {

    // For logging and debugging
    private static final String TAG = "NotesList";

    /**
     * The columns needed by the cursor adapter
     *
     * 【扩展】原版只查 _ID 和 title。现在还需要：
     * modified（时间戳）、note（内容摘要）、category（分类色条）。
     */
    private static final String[] PROJECTION = new String[] {
            NotePad.Notes._ID,                            // 0
            NotePad.Notes.COLUMN_NAME_TITLE,              // 1
            NotePad.Notes.COLUMN_NAME_MODIFICATION_DATE,  // 2
            NotePad.Notes.COLUMN_NAME_NOTE,               // 3
            NotePad.Notes.COLUMN_NAME_CATEGORY,           // 4
    };

    /** The index of the title column */
    private static final int COLUMN_INDEX_TITLE = 1;
    private static final int COLUMN_INDEX_MODIFIED = 2;
    private static final int COLUMN_INDEX_NOTE = 3;
    private static final int COLUMN_INDEX_CATEGORY = 4;

    /**
     * 原版写进 title 列的占位符。
     *
     * <p>NoteEditor 新建笔记时会往 title 写 "<Untitled>"，如果用户一直没编辑过标题，
     * 这个值就会留在库里。列表展示时要把它识别成“没有标题”而不是当成正常标题。
     */
    private static final String UNTITLED_PLACEHOLDER = "<Untitled>";

    /* ---------------- 【扩展】搜索与排序、筛选的界面状态 ---------------- */

    /** 当前搜索关键字，空串表示不搜索 */
    private String mQuery = "";

    /** 当前分类筛选，-1 表示“全部” */
    private int mCategoryFilter = -1;

    /** 当前排序方式，与 NotePad.Notes 里的 SORT_* 常量对应 */
    private String mSortOrder = NotePad.Notes.SORT_MODIFIED_DESC;

    /** 排序方式的下标，用于菜单勾选 */
    private static final String[] SORT_LABELS = new String[] {
            "修改时间（最新在前）", "修改时间（最早在前）", "创建时间（最新在前）", "标题（A→Z）"
    };
    private static final String[] SORT_ORDERS = new String[] {
            NotePad.Notes.SORT_MODIFIED_DESC,
            NotePad.Notes.SORT_MODIFIED_ASC,
            NotePad.Notes.SORT_CREATED_DESC,
            NotePad.Notes.SORT_TITLE_ASC
    };

    /*
     * 【扩展】自定义菜单项 id。
     *
     * 为什么用 0xE001 这种值而不是 R.id.xxx？
     * 因为这两个 SubMenu 是代码动态生成的，不在任何 menu xml 里，
     * 没有对应的 R.id 可用。取一个不会与 XML 资源 id 冲突的区间即可。
     */
    private static final int MENU_ID_SORT = 0xE001;
    private static final int MENU_ID_SORT_BASE = 0xE100;
    private static final int MENU_ID_THEME = 0xE002;
    private static final int MENU_ID_THEME_BASE = 0xE200;

    /*
     * 【扩展】长按菜单的动作编号。
     * 用从 0 开始的下标，正好和 NOTE_ACTION_LABELS 的数组下标一一对应，
     * AlertDialog.setItems 回调里的 which 可以直接当动作编号用。
     */
    private static final int NOTE_ACTION_OPEN = 0;
    private static final int NOTE_ACTION_CATEGORY = 1;
    private static final int NOTE_ACTION_COPY = 2;
    private static final int NOTE_ACTION_DELETE = 3;
    private static final String[] NOTE_ACTION_LABELS = new String[] {
            "打开笔记", "设置分类", "复制笔记", "删除笔记"
    };

    /*
     * 【扩展】界面状态在 Activity 重建时的存取 key。
     *
     * 切换主题会调用 recreate()，Activity 会被销毁重建，
     * 所有成员变量都会回到初始值。把"排序方式 / 分类筛选 / 搜索词"
     * 存进 savedInstanceState，重建后就能原样恢复。
     */
    private static final String STATE_SORT_ORDER = "state_sort_order";
    private static final String STATE_CATEGORY_FILTER = "state_category_filter";
    private static final String STATE_QUERY = "state_query";

    /** 界面控件引用 */
    private EditText mSearchInput;
    private ImageButton mSearchClear;
    private LinearLayout mCategoryFilterBar;
    private TextView mEmptyView;
    private TextView mStatusBar;
    private View mToolbar;
    private SimpleCursorAdapter mAdapter;

    /** 当前主题配色 */
    private ThemeHelper.Palette mPalette;

    /**
     * onCreate is called when Android starts this Activity from scratch.
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        /*
         * 【关键调用顺序】必须在 setContentView 之前关掉窗口自带的标题栏。
         *
         * ListActivity 会按 android:label 自动生成一条 TitleBar。
         * 我们改用自定义布局后，这条自动 TitleBar 与布局里的 toolbar_layout 重复，
         * 结果把搜索框往下压、被遮住一半（实测截图可见）。
         * requestWindowFeature 只能在 setContentView 之前调用，所以放在这里。
         */
        requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);

        /*
         * 【扩展】设置状态栏颜色，与自绘标题栏连成一片。
         * 关掉系统标题栏后，内容区会从状态栏下沿开始绘制；
         * 这里把状态栏也染成同一个深色，避免出现"白条 + 深色标题栏"的割裂感。
         */
        getWindow().setStatusBarColor(0xFF1F2933);

        // 【扩展】先取主题，后面的控件上色都依赖它
        mPalette = ThemeHelper.getPalette(this);

        /*
         * 【扩展】恢复上次的界面状态（排序 / 分类筛选 / 搜索词）。
         *
         * 必须放在 bindViews() 之前：筛选栏是按 mCategoryFilter 决定哪个胶囊高亮的，
         * 搜索框也要把 mQuery 回填进去，晚了就会先按默认状态画一遍再跳变。
         */
        restoreUiState(savedInstanceState);

        /*
         * 【关键实现】换成自定义布局，并保留 ListActivity 要求的窗口结构。
         *
         * 这里尝试过三种方案，最终采用第 3 种（细节见报告"踩坑记录"）：
         *
         * 方案1  setContentView(R.layout.notes_list) 但布局里没有 android.R.id.list
         *        -> 直接崩溃：Your content must have a ListView whose id attribute is
         *           'android.R.id.list'
         *
         * 方案2  用 addContentView() 把头部叠上去，或直接往 android.R.id.content 里 addView
         *        -> 不崩溃，但头部控件根本不显示：ListActivity 在 onContentChanged()
         *           之后会把内容层重新整理，叠加的 View 被挤出可视区。
         *
         * 方案3（采用）自定义布局里内嵌一个 id 为 @android:id/list 的 ListView，
         *        再 setContentView。ListActivity 的 onContentChanged() 能找到列表，
         *        getListView()/setListAdapter()/onListItemClick() 全部照常工作；
         *        唯一副作用是原来自动生成的标题栏不再显示，
         *        因此在布局顶部自绘了一个 toolbar_layout 承担标题栏职责。
         */
        setContentView(R.layout.notes_list);
        bindViews();

        // The user does not need to hold down the key for menu shortcuts.
        setDefaultKeyMode(DEFAULT_KEYS_SHORTCUT);

        /* If no data is given in the Intent that started this Activity, then this Activity
         * was started when the intent filter matched a MAIN action. We should use the default
         * provider URI.
         */
        // Gets the intent that started this Activity.
        Intent intent = getIntent();

        // If there is no data associated with the Intent, sets the data to the default URI, which
        // accesses a list of notes.
        if (intent.getData() == null) {
            intent.setData(NotePad.Notes.CONTENT_URI);
        }

        /*
         * 【改动点】长按列表项弹出"这条笔记能做什么"的菜单。
         *
         * 原版这里写的是 getListView().setOnCreateContextMenuListener(this)，
         * 配合下面的 onCreateContextMenu() + onContextItemSelected() 使用系统上下文菜单。
         *
         * 改造之后这套机制失效了：菜单本身能弹出来，但点任意一项，
         * onContextItemSelected() 一次都不会被回调。用日志确认过，
         * 连方法第一行都进不去 —— 原因是 ContextMenu 的点击事件不再回传给 Activity，
         * 于是 onContextItemSelected() 里所有分支都成了死代码，
         * 表现出来就是"长按菜单点了没反应"。
         *
         * 现在改成不依赖系统上下文菜单回调链的做法：
         *   setOnItemLongClickListener 拿到被长按的 position
         *   → 从 Adapter 的 Cursor 里取标题做对话框标题
         *   → AlertDialog.setItems 弹出选项列表
         *   → performNoteAction() 统一分发处理
         * 菜单内容、标题、执行结果全在自己手里，不受框架版本差异影响。
         */
        getListView().setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
                Cursor c = (Cursor) mAdapter.getItem(position);
                String title = (c == null) ? "" : c.getString(COLUMN_INDEX_TITLE);
                showNoteActionsDialog(id, title);
                // 返回 true 表示事件已消费，不再触发系统的上下文菜单
                return true;
            }
        });

        /* Performs a managed query. The Activity handles closing and requerying the cursor
         * when needed.
         *
         * Please see the introductory note about performing provider operations on the UI thread.
         *
         * 【扩展】这里不再直接查一次，而是调用 refreshList()：
         * 该方法会把“关键字 + 分类筛选 + 排序”组合成 selection / selectionArgs / orderBy，
         * 首次进入时参数为空，查询结果与原版等价。
         */
        Cursor cursor = detailQuery();

        /*
         * The following two arrays create a "map" between columns in the cursor and view IDs
         * for items in the ListView. Each element in the dataColumns array represents
         * a column name; each element in the viewID array represents the ID of a View.
         * The SimpleCursorAdapter maps them in ascending order to determine where each column
         * value will appear in the ListView.
         */

        // 【扩展】把时间戳、内容摘要、分类都交给 adapter 绑定，不再只绑定标题
        String[] dataColumns = {
                NotePad.Notes.COLUMN_NAME_TITLE,
                NotePad.Notes.COLUMN_NAME_MODIFICATION_DATE,
                NotePad.Notes.COLUMN_NAME_NOTE,
                NotePad.Notes.COLUMN_NAME_CATEGORY
        };

        int[] viewIDs = {
                android.R.id.text1,   // 标题
                R.id.note_time,       // 时间戳
                R.id.note_summary,    // 内容摘要
                R.id.note_category    // 分类标签
        };

        // Creates the backing adapter for the ListView.
        //
        // 【扩展】这里用到了 SimpleCursorAdapter 的三参构造之外的第 6 个参数：
        // SimpleCursorAdapter(Context, layout, cursor, from, to, flags)
        // 原版用的是已废弃的 5 参构造（没有 flags），在新 SDK 上会告警。
        // FLAG_REGISTER_CONTENT_OBSERVER 表示让 adapter 自己去观察 cursor 变化。
        mAdapter = new SimpleCursorAdapter(
                this,
                R.layout.noteslist_item,
                cursor,
                dataColumns,
                viewIDs,
                SimpleCursorAdapter.FLAG_REGISTER_CONTENT_OBSERVER
        );

        /*
         * 【扩展】SimpleCursorAdapter 只会把 cursor 里的值原样 setText，
         * 而我们需要：
         *   1) 把毫秒时间戳格式化成“今天 14:35”这种可读文本；
         *   2) 把正文截断成一行摘要；
         *   3) 按分类给左侧色条上色、给分类标签上色；
         *   4) 按主题设置文字颜色。
         * 这些都属于“绑定后加工”，所以用 ViewBinder 实现，而不是自己写 Adapter。
         */
        mAdapter.setViewBinder(new SimpleCursorAdapter.ViewBinder() {
            @Override
            public boolean setViewValue(View view, Cursor cursor, int columnIndex) {
                switch (columnIndex) {
                    case COLUMN_INDEX_TITLE:
                        /*
                         * 【修复】标题兜底。
                         *
                         * 原版在新建笔记时会往 title 列写入 "<Untitled>" 作为占位符，
                         * 空笔记如果没再编辑过，库里就一直是这个值，直接 setText 会让
                         * 中文界面上冒出 "<Untitled>" 这种英文占位符。
                         *
                         * 这里判断两种情况：空 / 纯空白 / 原版占位符，统一显示“无标题笔记”。
                         * 注意要把 <Untitled> 一起判掉——它不是空串，单判 isEmpty 拦不住。
                         */
                        String rawTitle = cursor.getString(columnIndex);
                        if (TextUtils.isEmpty(rawTitle)
                                || rawTitle.trim().length() == 0
                                || UNTITLED_PLACEHOLDER.equals(rawTitle.trim())) {
                            ((TextView) view).setText(R.string.hint_no_title);
                        } else {
                            ((TextView) view).setText(rawTitle);
                        }
                        // 标题颜色跟随主题（深色主题下必须是浅色字，否则看不见）
                        ((TextView) view).setTextColor(mPalette.textPrimary);
                        return true;

                    case COLUMN_INDEX_MODIFIED:
                        // 【基本要求 1】时间戳显示
                        ((TextView) view).setText(
                                TimeUtils.formatFriendlyTime(cursor.getLong(columnIndex)));
                        ((TextView) view).setTextColor(mPalette.textSecondary);
                        return true;

                    case COLUMN_INDEX_NOTE:
                        // 正文可能多行，压成一行做摘要
                        String body = cursor.getString(columnIndex);
                        if (body == null) {
                            body = "";
                        }
                        ((TextView) view).setText(body.replace('\n', ' ').trim());
                        ((TextView) view).setTextColor(mPalette.textSecondary);
                        return true;

                    case COLUMN_INDEX_CATEGORY:
                        bindCategory(view, cursor.getInt(columnIndex));
                        return true;

                    default:
                        // 返回 false 表示交给 SimpleCursorAdapter 默认逻辑处理
                        return false;
                }
            }
        });

        // Sets the ListView's adapter to be the cursor adapter that was just created.
        setListAdapter(mAdapter);
        updateEmptyState(cursor);

        // 内容区按主题上色，并给列表让出顶部/底部空间
        applyTheme();
        applyListInsets();
    }

    /**
     * 【扩展】给扩展控件绑定交互，并设置自绘标题栏。
     */
    private void bindViews() {
        mSearchInput = findViewById(R.id.search_input);
        mSearchClear = findViewById(R.id.search_clear);
        mCategoryFilterBar = findViewById(R.id.category_filter);
        mEmptyView = findViewById(R.id.empty_view);
        mStatusBar = findViewById(R.id.status_bar);
        mToolbar = findViewById(R.id.toolbar_layout);

        // 自绘标题栏文字
        TextView title = findViewById(R.id.toolbar_title);
        if (title != null) {
            title.setText(getTitle());
        }
        // 新建按钮：等价于原版工具栏里的 menu_add
        findViewById(R.id.btn_add).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(Intent.ACTION_INSERT, getIntent().getData()));
            }
        });
        // 更多按钮：弹出选项菜单（排序、主题、粘贴等都在里面）
        findViewById(R.id.btn_more).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                /*
                 * 【实现方式说明】这里用 PopupMenu 而不是 openOptionsMenu()。
                 *
                 * 原因是 Activity 的 OptionsMenu + SubMenu 在"自绘标题栏 + 没有系统
                 * ActionBar"的组合下表现不稳定：SubMenu 展开需要一套挂载在当前窗口
                 * token 上的弹出层，缺少 ActionBar 时容易点开即消失。
                 *
                 * PopupMenu 直接锚定在"更多"这个 View 上，弹出位置和层级都由自家
                 * View 决定，不依赖 ActionBar，行为稳定，也更贴近现在的 Material 风格。
                 */
                showMoreMenu(v);
            }
        });

        // 把重建前用户输入的搜索词回填到输入框（此刻还没挂 TextWatcher，不会触发重复查询）
        if (!TextUtils.isEmpty(mQuery)) {
            mSearchInput.setText(mQuery);
            mSearchClear.setVisibility(View.VISIBLE);
        }

        /*
         * 【基本要求 2】搜索交互设计说明：
         * 采用“输入即过滤 + 回车立即收起键盘”的组合。
         * 每输入一个字符就调用一次 refreshList() 重新查询（实时搜索体验）。
         * 数据量小时开销可以接受；如果数据量大，应改成 AsyncQueryHandler + 300ms 防抖，
         * 见报告“可优化点”一节。
         */
        mSearchInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                mQuery = s.toString().trim();
                mSearchClear.setVisibility(mQuery.isEmpty() ? View.GONE : View.VISIBLE);
                refreshList();
            }
        });

        // 回车/搜索键：收起键盘，结果已经实时刷新
        mSearchInput.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                    hideKeyboard();
                    return true;
                }
                return false;
            }
        });

        mSearchClear.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mSearchInput.setText("");
            }
        });

        // 【扩展】分类筛选栏动态生成按钮
        buildCategoryFilterBar();
    }

    /**
     * 【扩展】弹出"更多"菜单（新建 / 粘贴 / 排序方式 / 主题）。
     *
     * <p>用 {@link PopupMenu} 实现。菜单结构：
     * <pre>
     *   新建笔记
     *   粘贴
     *   ─────────
     *   排序方式 ▸  修改时间（最新在前）
     *              修改时间（最早在前）
     *              创建时间（最新在前）
     *              标题（A→Z）
     *   ─────────
     *   主题 ▸      浅色·蓝 / 浅色·纸黄 / 深色 / 浅色·绿
     * </pre>
     *
     * <p>【为什么不用 Activity 的 OptionsMenu】
     * 本项目没有 ActionBar（NoActionBar 主题 + 自绘标题栏），
     * OptionsMenu 的 SubMenu 在这种结构下点开容易立即消失（PopupWindow token 问题）。
     * PopupMenu 锚定到具体按钮，行为稳定。
     *
     * @param anchor 锚点 View（这里是"更多"按钮）
     */
    private void showMoreMenu(View anchor) {
        android.widget.PopupMenu popup = new android.widget.PopupMenu(this, anchor);
        Menu menu = popup.getMenu();

        // ---- 第一组：基础操作 ----
        menu.add(Menu.NONE, R.id.menu_add, 0, "新建笔记");
        MenuItem paste = menu.add(Menu.NONE, R.id.menu_paste, 1, "粘贴");
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        paste.setEnabled(clipboard != null && clipboard.hasPrimaryClip());

        /*
         * 【设计说明】排序方式和主题都做成"普通菜单项"，点了之后弹 AlertDialog 单选列表。
         *
         * 为什么不用 SubMenu？
         * SubMenu 需要额外一层挂在当前窗口 token 上的弹出层，在本项目这套
         * "NoActionBar + 自绘标题栏 + PopupMenu" 的结构下，点开会连带把父菜单一起关掉
         * （表现为"点排序方式 → 菜单整个消失，子菜单也没出现"）。
         * 改用 AlertDialog 只有一个系统对话框窗口，行为最稳定；
         * 而且单选项能完整显示文字，不会被挤成两行或被截断。
         */
        menu.add(Menu.NONE, MENU_ID_SORT, 2, "排序方式");
        menu.add(Menu.NONE, MENU_ID_THEME, 3, "主题");

        popup.setOnMenuItemClickListener(new android.widget.PopupMenu.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem item) {
                int id = item.getItemId();
                if (id == R.id.menu_add) {
                    startActivity(new Intent(Intent.ACTION_INSERT, getIntent().getData()));
                    return true;
                }
                if (id == R.id.menu_paste) {
                    startActivity(new Intent(Intent.ACTION_PASTE, getIntent().getData()));
                    return true;
                }
                if (id == MENU_ID_SORT) {
                    showSortDialog();
                    return true;
                }
                if (id == MENU_ID_THEME) {
                    showThemeDialog();
                    return true;
                }
                return onOptionsItemSelected(item);
            }
        });
        popup.show();
    }

    /**
     * 【扩展】排序方式选择对话框。
     *
     * <p>选完后调用 refreshList() 重新查询，列表立刻按新顺序排列。
     * 排序只改变 ContentResolver.query 的 orderBy 参数，不动数据库，开销就是一次普通查询。
     */
    private void showSortDialog() {
        int checked = 0;
        for (int i = 0; i < SORT_ORDERS.length; i++) {
            if (SORT_ORDERS[i].equals(mSortOrder)) {
                checked = i;
                break;
            }
        }
        new AlertDialog.Builder(this)
                .setTitle("排序方式")
                .setSingleChoiceItems(SORT_LABELS, checked, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        mSortOrder = SORT_ORDERS[which];
                        refreshList();
                        dialog.dismiss();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /**
     * 【扩展】主题选择对话框。
     *
     * <p>选完后写入 SharedPreferences 并调用 recreate()。
     * recreate() 会销毁并重建 Activity，重建时 ThemeHelper.getPalette() 读到新主题，
     * 所有控件按新配色重新上色，效果等同于"换肤"，同时避免手工逐个控件改色漏项。
     */
    private void showThemeDialog() {
        new AlertDialog.Builder(this)
                .setTitle("主题")
                .setSingleChoiceItems(ThemeHelper.THEME_NAMES, ThemeHelper.getTheme(this),
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                ThemeHelper.setTheme(NotesList.this, which);
                                dialog.dismiss();
                                recreate();
                            }
                        })
                .setNegativeButton("取消", null)
                .show();
    }

    /**
     * 【扩展】生成“全部 + 各分类”的横向筛选按钮。
     *
     * <p>之所以用代码动态生成而不是写死 5 个 TextView：
     * 分类数量定义在 {@link CategoryHelper} 里，将来加减分类只改一处即可。
     */
    private void buildCategoryFilterBar() {
        mCategoryFilterBar.removeAllViews();

        // “全部”按钮，用 -1 表示不筛选
        addFilterChip("全部", -1, Color.parseColor("#607D8B"));

        String[] names = CategoryHelper.getAllNames();
        for (int i = 0; i < names.length; i++) {
            addFilterChip(names[i], i, CategoryHelper.getColor(i));
        }
    }

    /**
     * 向筛选栏追加一个“胶囊按钮”。
     *
     * @param label    显示文字
     * @param category 对应分类 id，-1 表示全部
     * @param color    该分类的标识色
     */
    private void addFilterChip(String label, final int category, final int color) {
        TextView chip = new TextView(this);
        chip.setText(label);
        chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        chip.setPadding(dp(12), dp(6), dp(12), dp(6));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(6);
        chip.setLayoutParams(lp);

        // 选中态：实心底 + 白字；未选中：浅底 + 深字
        boolean selected = (mCategoryFilter == category);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(20));
        if (selected) {
            bg.setColor(color);
            chip.setTextColor(Color.WHITE);
        } else {
            bg.setColor(withAlpha(color, 0x22));
            chip.setTextColor(mPalette.textPrimary);
        }
        chip.setBackground(bg);

        chip.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mCategoryFilter = category;
                buildCategoryFilterBar();   // 重建以刷新选中态
                refreshList();
            }
        });

        mCategoryFilterBar.addView(chip);
    }

    /**
     * 【扩展·核心方法】按当前“关键字 + 分类 + 排序”重新查询并刷新列表。
     *
     * <p>这里用 changeCursor() 而不是重新 new 一个 SimpleCursorAdapter，
     * 原因：changeCursor 会自己关掉旧 cursor、换上新的，Adapter 实例不变，
     * ListView 的滚动位置和已绑定的 View 都能复用，不会整屏闪烁。
     */
    private void refreshList() {
        Cursor newCursor = detailQuery();

        // 复用同一个 adapter，只换 cursor，避免列表闪烁、也避免反复 new adapter
        mAdapter.changeCursor(newCursor);
        updateEmptyState(newCursor);
    }

    /**
     * 执行一次带完整条件的查询。
     *
     * <p>【实现要点】ContentProvider 的 query() 参数：
     * <pre>
     *   query(uri, projection, selection, selectionArgs, orderBy)
     * </pre>
     * selection 是 SQL 的 WHERE 片段，selectionArgs 里的 '?' 占位符会被依次替换，
     * 这样既能把“分类”和“关键字”两个条件用 AND 组合起来，又能避免 SQL 注入。
     *
     * @return 查询结果 cursor
     */
    private Cursor detailQuery() {
        Uri uri = getIntent().getData() != null
                ? getIntent().getData()
                : NotePad.Notes.CONTENT_URI;

        StringBuilder selection = new StringBuilder();
        java.util.List<String> args = new java.util.ArrayList<String>();

        // 条件一：分类筛选
        if (mCategoryFilter >= 0) {
            selection.append(NotePad.Notes.COLUMN_NAME_CATEGORY).append(" = ?");
            args.add(String.valueOf(mCategoryFilter));
        }

        // 条件二：关键字（标题 OR 正文，两者都匹配）
        if (!TextUtils.isEmpty(mQuery)) {
            if (selection.length() > 0) {
                selection.append(" AND ");
            }
            String like = "%" + mQuery + "%";
            /*
             * 标题和正文都要匹配，整体用括号包起来，避免和前面的分类条件
             * 混在一起变成 "category=? AND title LIKE ? OR note LIKE ?" ——
             * 那样 OR 的优先级会让分类筛选失效。
             */
            selection.append("(")
                    .append(NotePad.Notes.COLUMN_NAME_TITLE).append(" LIKE ?");
            args.add(like);
            selection.append(" OR ")
                    .append(NotePad.Notes.COLUMN_NAME_NOTE).append(" LIKE ?");
            args.add(like);
            selection.append(")");
        }

        String selectionStr = selection.length() == 0 ? null : selection.toString();
        String[] selectionArgs = args.isEmpty() ? null : args.toArray(new String[0]);

        /*
         * 【重要改动】原版用 managedQuery()，这里改用 getContentResolver().query()。
         *
         * 本页会频繁在搜索/筛选/排序时重新查询，managedQuery 得到的 cursor 由
         * Activity 托管并可能在 onStop 时被框架关闭，后续 changeCursor 再操作它就会抛
         * "trying to requery an already closed cursor"。
         * 改成自己 query 之后 cursor 的生命周期由 Adapter 管理（changeCursor 会关闭旧
         * cursor），Activity 销毁时再统一 close 一次即可。
         */
        return getContentResolver().query(uri, PROJECTION, selectionStr, selectionArgs, mSortOrder);
    }

    /**
     * 【扩展】按分类设置左侧色条和分类标签。
     *
     * @param view     该行被绑定的 View（分类标签 TextView）
     * @param category 分类 id
     */
    private void bindCategory(View view, int category) {
        /*
         * ViewBinder 只拿到“目标 View”，拿不到整行。这里用 getParent() 两次
         * 找到整行的根布局再找色条：
         *   note_category(TextView) -> LinearLayout(vertical) -> LinearLayout(horizontal 行根)
         */
        View row = (View) view.getParent().getParent();
        View bar = row.findViewById(R.id.category_bar);
        TextView label = (TextView) view;

        int color = CategoryHelper.getLabelColor(this, category);
        if (bar != null) {
            bar.setBackgroundColor(color);
        }

        // 分类为“默认”时不显示标签，减少视觉噪音
        if (category == NotePad.Notes.CATEGORY_DEFAULT) {
            label.setVisibility(View.GONE);
        } else {
            label.setVisibility(View.VISIBLE);
            label.setText(CategoryHelper.getName(category));
            label.setTextColor(color);
            // 半透明底色 + 圆角，形成“小徽章”效果
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(4));
            bg.setColor(withAlpha(color, 0x1A));
            label.setBackground(bg);
            label.setPadding(dp(6), dp(2), dp(6), dp(2));
        }
    }

    /**
     * 【扩展】把当前主题配色应用到界面各部件。
     */
    private void applyTheme() {
        /*
         * 【关键】根布局背景必须一起改。
         *
         * 布局文件里写死了浅色 #F2F4F8，如果这里只改 ListView 的底色，
         * 深色主题下"列表以外的区域"（筛选栏、列表下方留白）仍是浅色，
         * 看起来就是"切了深色但只有列表变黑"，像没生效一样。
         */
        findViewById(android.R.id.content).setBackgroundColor(mPalette.background);
        View root = findViewById(R.id.toolbar_layout).getRootView();
        if (root != null) {
            root.setBackgroundColor(mPalette.background);
        }

        /*
         * 顶栏背景：用 topBar 而不是 accent。
         * 深色主题的 accent 是浅蓝（要够亮才在深底上看得清），
         * 直接铺成整条顶栏会变成刺眼的浅色大色块，所以两套色分开。
         */
        mToolbar.setBackgroundColor(mPalette.topBar);
        // 状态栏与顶栏同色，连成一片，避免出现一条割裂的色带
        getWindow().setStatusBarColor(mPalette.topBar);

        // 顶栏上的标题文字
        TextView toolbarTitle = findViewById(R.id.toolbar_title);
        if (toolbarTitle != null) {
            toolbarTitle.setTextColor(mPalette.onTopBar);
        }
        // 顶栏上的两个图标按钮：VectorDrawable 用 tint 上色，跟主题保持一致
        View addBtn = findViewById(R.id.btn_add);
        if (addBtn instanceof android.widget.ImageView) {
            ((android.widget.ImageView) addBtn).setColorFilter(mPalette.onTopBar);
        }
        View moreBtn = findViewById(R.id.btn_more);
        if (moreBtn instanceof android.widget.ImageView) {
            ((android.widget.ImageView) moreBtn).setColorFilter(mPalette.onTopBar);
        }

        // 分类筛选栏所在容器（HorizontalScrollView 的父层）跟随页面底色
        View filterBar = findViewById(R.id.category_filter);
        if (filterBar != null && filterBar.getParent() != null
                && filterBar.getParent().getParent() instanceof View) {
            ((View) filterBar.getParent().getParent()).setBackgroundColor(mPalette.background);
        }

        // 列表背景：用条目底色，和外层页面底色形成层次
        getListView().setBackgroundColor(mPalette.itemBackground);

        // 搜索区背景（页面底色）
        findViewById(R.id.search_container).setBackgroundColor(mPalette.background);

        /*
         * 【踩坑记录】最初把输入框背景设成和页面同色、又没有描边，
         * 在浅色主题下根本看不出这里有个输入框，误以为“搜索栏没显示”。
         * 现在输入框用 searchBar 色 + 1dp 描边，任何主题下边界都清晰。
         */
        GradientDrawable searchBg = new GradientDrawable();
        searchBg.setCornerRadius(dp(18));
        searchBg.setColor(mPalette.searchBar);
        searchBg.setStroke(dp(1), mPalette.divider);
        mSearchInput.setBackground(searchBg);
        mSearchInput.setTextColor(mPalette.textPrimary);
        mSearchInput.setHintTextColor(mPalette.textSecondary);

        // 底部统计条
        mStatusBar.setTextColor(mPalette.textSecondary);
        mStatusBar.setBackgroundColor(mPalette.background);

        // 搜索清除按钮跟随主题着色
        mSearchClear.setColorFilter(mPalette.textSecondary);

        // 空提示文字
        mEmptyView.setTextColor(mPalette.textSecondary);

        // 列表分割线
        getListView().setDivider(new android.graphics.drawable.ColorDrawable(mPalette.divider));
        getListView().setDividerHeight(dp(1));
    }

    /**
     * 【扩展】调整列表区域的留白。
     *
     * <p>因为布局是"标题栏 + 搜索 + 筛选 + 列表 + 统计条"的纵向排列，
     * 列表本身不需要额外 padding；这里保留一个方法是为了后续要加"悬浮按钮"
     * 之类覆盖式控件时集中处理底部遮挡问题。
     */
    private void applyListInsets() {
        getListView().setPadding(0, 0, 0, 0);
    }

    /**
     * 【扩展】刷新底部统计信息与空提示。
     *
     * <p>原版没有“空列表提示”，这里用统计条 + 居中提示文字表达列表状态：
     * 搜索无结果时会显示“没有找到匹配的笔记”，比一片空白更明确。
     */
    private void updateEmptyState(Cursor cursor) {
        int count = (cursor == null) ? 0 : cursor.getCount();

        // 空列表提示只在“一条都没有”时出现
        mEmptyView.setVisibility(count == 0 ? View.VISIBLE : View.GONE);
        // 空提示出现时，把列表隐藏，避免分割线透出来
        getListView().setVisibility(count == 0 ? View.INVISIBLE : View.VISIBLE);

        if (TextUtils.isEmpty(mQuery) && mCategoryFilter < 0) {
            mStatusBar.setText("共 " + count + " 条笔记 · 排序：" + currentSortLabel());
        } else if (count == 0) {
            mStatusBar.setText("没有找到匹配的笔记 · 试试其他关键字或分类");
        } else {
            mStatusBar.setText("找到 " + count + " 条 · 排序：" + currentSortLabel());
        }
        mStatusBar.setTextColor(mPalette.textSecondary);
    }

    /** 当前排序方式的显示名 */
    private String currentSortLabel() {
        for (int i = 0; i < SORT_ORDERS.length; i++) {
            if (SORT_ORDERS[i].equals(mSortOrder)) {
                return SORT_LABELS[i];
            }
        }
        return SORT_LABELS[0];
    }

    /** 收起软键盘 */
    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.hideSoftInputFromWindow(mSearchInput.getWindowToken(), 0);
        }
    }

    /** dp 转 px */
    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    /** 给颜色加透明度，用于浅色底 */
    private int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (alpha << 24);
    }

    /**
     * Called when the user clicks the device's Menu button the first time for
     * this Activity. Android passes in a Menu object that is populated with items.
     *
     * Sets up a menu that provides the Insert option plus a list of alternative actions for
     * this Activity. Other applications that want to handle notes can "register" themselves in
     * Android by providing an intent filter that includes the category ALTERNATIVE and the
     * mimeTYpe NotePad.Notes.CONTENT_TYPE. If they do this, the code in onCreateOptionsMenu()
     * will add the Activity that contains the intent filter to its list of options. In effect,
     * the menu will offer the user other applications that can handle notes.
     * @param menu A Menu object, to which menu items should be added.
     * @return True, always. The menu should be displayed.
     */
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate menu from XML resource
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.list_options_menu, menu);

        /*
         * 【扩展】在菜单里追加"排序方式"和"主题"两个 SubMenu。
         *
         * 【踩坑记录】这里必须先判断 findItem() 是否已经存在。
         * onCreateOptionsMenu() 在部分场景（尤其配合 onPrepareOptionsMenu 动态改菜单时）
         * 会被重复调用，如果不判断，同一份菜单里会出现两个"排序方式"，
         * 点击时框架无法匹配到唯一的 item，菜单会"闪现后立刻消失"。
         */
        if (menu.findItem(MENU_ID_SORT) == null) {
            android.view.SubMenu sortMenu = menu.addSubMenu(Menu.NONE, MENU_ID_SORT, Menu.NONE, "排序方式");
            // 整组设为"单选"：同组内只能有一项被勾选
            sortMenu.setGroupCheckable(Menu.NONE, true, true);
            for (int i = 0; i < SORT_LABELS.length; i++) {
                MenuItem item = sortMenu.add(Menu.NONE, MENU_ID_SORT_BASE + i, i, SORT_LABELS[i]);
                // 单选框样式，勾选当前排序
                item.setCheckable(true);
                item.setChecked(SORT_ORDERS[i].equals(mSortOrder));
            }
        }

        if (menu.findItem(MENU_ID_THEME) == null) {
            android.view.SubMenu themeMenu = menu.addSubMenu(Menu.NONE, MENU_ID_THEME, Menu.NONE, "主题");
            themeMenu.setGroupCheckable(Menu.NONE, true, true);
            for (int i = 0; i < ThemeHelper.THEME_NAMES.length; i++) {
                MenuItem item = themeMenu.add(Menu.NONE, MENU_ID_THEME_BASE + i, i, ThemeHelper.THEME_NAMES[i]);
                item.setCheckable(true);
                item.setChecked(ThemeHelper.getTheme(this) == i);
            }
        }

        // Generate any additional actions that can be performed on the
        // overall list.  In a normal install, there are no additional
        // actions found here, but this allows other applications to extend
        // our menu with their own actions.
        //
        // 【注意】这里只保留"列表整体"的扩展项，且因为 onCreateOptionsMenu 可能被重复调用，
        // 先清空同一分组再添加，避免重复累加（否则菜单会越开越长、甚至闪退）。
        menu.removeGroup(Menu.CATEGORY_ALTERNATIVE);
        Intent intent = new Intent(null, getIntent().getData());
        intent.addCategory(Intent.CATEGORY_ALTERNATIVE);
        menu.addIntentOptions(Menu.CATEGORY_ALTERNATIVE, 0, 0,
                new ComponentName(this, NotesList.class), null, intent, 0, null);

        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        super.onPrepareOptionsMenu(menu);

        /*
         * 【踩坑记录·必须看】onPrepareOptionsMenu 会在"每次菜单弹出前"被调用，
         * 而原版这里用 menu.addIntentOptions(...) 动态追加 ALTERNATIVE 项。
         *
         * 如果每次调用都无条件追加，菜单会被反复改写：
         *   第一次打开：5 项 → 弹出正常
         *   第二次打开：又追加一批 → 项数翻倍 → PopupWindow 高度反复测量
         * 表现出来的现象就是"菜单一闪就消失"（尤其 SubMenu 点不开）。
         *
         * 所以第一步先清掉上一轮动态加进去的 ALTERNATIVE 组，再重建。
         */
        menu.removeGroup(Menu.CATEGORY_ALTERNATIVE);

        // The paste menu item is enabled if there is data on the clipboard.
        ClipboardManager clipboard = (ClipboardManager)
                getSystemService(Context.CLIPBOARD_SERVICE);

        MenuItem mPasteItem = menu.findItem(R.id.menu_paste);

        /*
         * 【空指针防护】某些时机（例如 onCreateOptionsMenu 还没跑到）findItem 会返回 null，
         * 原版直接调用会 NPE 崩溃，这里加一层判断。
         */
        if (mPasteItem != null) {
            // If the clipboard contains an item, enables the Paste option on the menu.
            mPasteItem.setEnabled(clipboard != null && clipboard.hasPrimaryClip());
        }

        // Gets the number of notes currently being displayed.
        final boolean haveItems = getListAdapter().getCount() > 0;

        // If there are any notes in the list (which implies that one of
        // them is selected), then we need to generate the actions that
        // can be performed on the current selection.  This will be a combination
        // of our own specific actions along with any extensions that can be
        // found.
        if (haveItems) {

            /*
             * 【踩坑记录·闪退根因】原版这里直接写
             *   Uri uri = ContentUris.withAppendedId(getIntent().getData(), getSelectedItemId());
             *
             * 但 onPrepareOptionsMenu 被调用时，列表里**未必有选中项**
             * （用户只是点了右上角"更多"，从没点过列表行）。
             * 此时 getSelectedItemId() 返回 AdapterView.INVALID_ROW_ID = Long.MIN_VALUE，
             * 拼出来的 URI 是 content://.../notes/-9223372036854775808，
             * NotePadProvider.getType() 抛 IllegalArgumentException，
             * 菜单构建过程被打断 → 表现就是"点更多按钮，菜单一闪就没了"。
             *
             * 修复：只有拿到合法 id 才做 ALTERNATIVE 扩展。
             */
            long selectedId = getSelectedItemId();
            if (selectedId == AdapterView.INVALID_ROW_ID || selectedId < 0) {
                // 没有选中行，跳过 ALTERNATIVE 扩展（上面的 removeGroup 已清干净）
                return true;
            }

            // This is the selected item.
            Uri uri = ContentUris.withAppendedId(getIntent().getData(), selectedId);

            // Creates an array of Intents with one element. This will be used to send an Intent
            // based on the selected menu item.
            Intent[] specifics = new Intent[1];

            // Sets the Intent in the array to be an EDIT action on the URI of the selected note.
            specifics[0] = new Intent(Intent.ACTION_EDIT, uri);

            // Creates an array of menu items with one element. This will contain the EDIT option.
            MenuItem[] items = new MenuItem[1];

            // Creates an Intent with no specific action, using the URI of the selected note.
            Intent intent = new Intent(null, uri);

            /* Adds the category ALTERNATIVE to the Intent, with the note ID URI as its
             * data. This prepares the Intent as a place to group alternative options in the
             * menu.
             */
            intent.addCategory(Intent.CATEGORY_ALTERNATIVE);

            /*
             * Add alternatives to the menu
             */
            menu.addIntentOptions(
                Menu.CATEGORY_ALTERNATIVE,  // Add the Intents as options in the alternatives group.
                Menu.NONE,                  // A unique item ID is not required.
                Menu.NONE,                  // The alternatives don't need to be in order.
                null,                       // The caller's name is not excluded from the group.
                specifics,                  // These specific options must appear first.
                intent,                     // These Intent objects map to the options in specifics.
                Menu.NONE,                  // No flags are required.
                items                       // The menu items generated from the specifics-to-
                                            // Intents mapping
            );
                // If the Edit menu item exists, adds shortcuts for it.
                if (items[0] != null) {

                    // Sets the Edit menu item shortcut to numeric "1", letter "e"
                    items[0].setShortcut('1', 'e');
                }
            } else {
                // If the list is empty, removes any existing alternative actions from the menu
                menu.removeGroup(Menu.CATEGORY_ALTERNATIVE);
            }

        // Displays the menu
        return true;
    }

    /**
     * This method is called when the user selects an option from the menu, but no item
     * in the list is selected. If the option was INSERT, then a new Intent is sent out with action
     * ACTION_INSERT. The data from the incoming Intent is put into the new Intent. In effect,
     * this triggers the NoteEditor activity in the NotePad application.
     *
     * If the item was not INSERT, then most likely it was an alternative option from another
     * application. The parent method is called to process the item.
     * @param item The menu item that was selected by the user
     * @return True, if the INSERT menu item was selected; otherwise, the result of calling
     * the parent method.
     */
    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int itemId = item.getItemId();

        if (itemId == R.id.menu_add) {
            /*
             * Launches a new Activity using an Intent. The intent filter for the Activity
             * has to have action ACTION_INSERT. No category is set, so DEFAULT is assumed.
             * In effect, this starts the NoteEditor Activity in NotePad.
             */
            startActivity(new Intent(Intent.ACTION_INSERT, getIntent().getData()));
            return true;
        } else if (itemId == R.id.menu_paste) {
            /*
             * Launches a new Activity using an Intent. The intent filter for the Activity
             * has to have action ACTION_PASTE. No category is set, so DEFAULT is assumed.
             * In effect, this starts the NoteEditor Activity in NotePad.
             */
            startActivity(new Intent(Intent.ACTION_PASTE, getIntent().getData()));
            return true;
        }

        // 【扩展】排序方式选择
        if (itemId >= MENU_ID_SORT_BASE && itemId < MENU_ID_SORT_BASE + SORT_ORDERS.length) {
            mSortOrder = SORT_ORDERS[itemId - MENU_ID_SORT_BASE];
            refreshList();
            return true;
        }

        // 【扩展】主题切换：切换后重建 Activity，让新主题立即生效
        if (itemId >= MENU_ID_THEME_BASE && itemId < MENU_ID_THEME_BASE + ThemeHelper.THEME_NAMES.length) {
            ThemeHelper.setTheme(this, itemId - MENU_ID_THEME_BASE);
            recreate();
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    /**
     * 【扩展·改动点】长按列表项弹出的操作菜单（替代原版的系统上下文菜单）。
     *
     * <p>原版用 onCreateContextMenu() 往系统 ContextMenu 里塞条目，再用
     * onContextItemSelected() 接收点击。改造后这套回调链在本工程里失效：菜单能弹出来，
     * 但点击任意一项，onContextItemSelected() 一次都不会被调用，于是所有分支都成了死代码，
     * 表现出来就是"长按菜单点了没反应"。
     *
     * <p>现在直接用 AlertDialog 列出选项，逻辑与原版等价：打开、设置分类、复制、删除。
     *
     * @param noteId 被长按笔记在 notes 表里的 _ID
     * @param title  该笔记的标题，用作对话框标题
     */
    private void showNoteActionsDialog(final long noteId, String title) {
        String header = TextUtils.isEmpty(title) ? "未命名笔记" : title;

        new AlertDialog.Builder(this)
                .setTitle(header)
                // setItems 走的是"单选列表"样式，which 就是数组下标，直接对应 NOTE_ACTION_*
                .setItems(NOTE_ACTION_LABELS, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        performNoteAction(noteId, which);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /**
     * 【扩展·改动点】统一处理"单条笔记"上的操作。
     *
     * <p>抽成一个方法，是为了让长按菜单和菜单按钮里的"粘贴/新建"等入口共用同一套逻辑，
     * 避免同一个动作在两处各写一遍、以后改一处漏一处。
     *
     * @param noteId 目标笔记的 _ID
     * @param action NOTE_ACTION_* 常量之一
     */
    private void performNoteAction(long noteId, int action) {
        /*
         * 把纯 id 拼成 content://com.example.android.notepad/notes/<id> 的形式。
         * provider 的 UriMatcher 里专门有一条 NOTE_ID 规则匹配这种"带 id"的 URI，
         * 匹配到之后就可以操作单独一行，而不是整张表。
         */
        Uri base = getIntent().getData();
        if (base == null) {
            base = NotePad.Notes.CONTENT_URI;
        }
        Uri noteUri = ContentUris.withAppendedId(base, noteId);

        switch (action) {
            case NOTE_ACTION_OPEN: {
                // 交给 NoteEditor，它按 Intent.ACTION_EDIT + 带 id 的 URI 打开已有笔记
                startActivity(new Intent(Intent.ACTION_EDIT, noteUri));
                break;
            }
            case NOTE_ACTION_CATEGORY: {
                // 分类选择框，选中后回写 category 列（见 showCategoryDialog）
                showCategoryDialog(noteId);
                break;
            }
            case NOTE_ACTION_COPY: {
                /*
                 * 复制的是 URI 而不是正文：URI 本身就是这条笔记的"地址"，
                 * 其他应用拿到后可以通过 ContentResolver 读走整条记录。
                 */
                ClipboardManager clipboard = (ClipboardManager)
                        getSystemService(Context.CLIPBOARD_SERVICE);
                if (clipboard != null) {
                    clipboard.setPrimaryClip(ClipData.newUri(
                            getContentResolver(),   // 用来解析 URI
                            "Note",                 // 剪贴板条目的标签
                            noteUri));              // 笔记的 URI
                    Toast.makeText(this, "笔记已复制到剪贴板", Toast.LENGTH_SHORT).show();
                }
                break;
            }
            case NOTE_ACTION_DELETE: {
                // 只传带 id 的 URI，where 传 null，provider 就知道只删这一行
                getContentResolver().delete(noteUri, null, null);
                // 删完立刻重查，列表和底部统计条一起更新
                refreshList();
                Toast.makeText(this, "已删除", Toast.LENGTH_SHORT).show();
                break;
            }
            default:
                break;
        }
    }

    /**
     * 【扩展】弹出分类选择对话框，选择后写回数据库。
     *
     * <p>这里直接把分类写进 notes 表，而不是新建一张 category 表：
     * 一个笔记只属于一个分类，属于“一对一”关系，用一列整数就够了；
     * 新建表反而要拉关联查询，对本项目的小数据量是过度设计。
     *
     * @param noteId 笔记的 rowId
     */
    private void showCategoryDialog(final long noteId) {
        final String[] names = CategoryHelper.getAllNames();
        int current = 0;

        // 先读出当前分类，作为对话框的默认选中项
        Cursor c = getContentResolver().query(
                ContentUris.withAppendedId(NotePad.Notes.CONTENT_ID_URI_BASE, noteId),
                new String[] { NotePad.Notes.COLUMN_NAME_CATEGORY }, null, null, null);
        if (c != null) {
            if (c.moveToFirst()) {
                current = c.getInt(0);
            }
            c.close();
        }

        new AlertDialog.Builder(this)
                .setTitle("设置分类")
                .setSingleChoiceItems(names, current, new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        android.content.ContentValues values = new android.content.ContentValues();
                        values.put(NotePad.Notes.COLUMN_NAME_CATEGORY, which);
                        // 同步更新修改时间，保证分类调整后排序位置合理
                        values.put(NotePad.Notes.COLUMN_NAME_MODIFICATION_DATE,
                                System.currentTimeMillis());
                        getContentResolver().update(
                                ContentUris.withAppendedId(NotePad.Notes.CONTENT_ID_URI_BASE, noteId),
                                values, null, null);
                        dialog.dismiss();
                        refreshList();
                        Toast.makeText(NotesList.this,
                                "已归入「" + names[which] + "」", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /**
     * This method is called when the user clicks a note in the displayed list.
     *
     * This method handles incoming actions of either PICK (get data from the provider) or
     * GET_CONTENT (get or create data). If the incoming action is EDIT, this method sends a
     * new Intent to start NoteEditor.
     * @param l The ListView that contains the clicked item
     * @param v The View of the individual item
     * @param position The position of v in the displayed list
     * @param id The row ID of the clicked item
     */
    @Override
    protected void onListItemClick(ListView l, View v, int position, long id) {

        // Constructs a new URI from the incoming URI and the row ID
        Uri uri = ContentUris.withAppendedId(getIntent().getData(), id);

        // Gets the action from the incoming Intent
        String action = getIntent().getAction();

        // Handles requests for note data
        if (Intent.ACTION_PICK.equals(action) || Intent.ACTION_GET_CONTENT.equals(action)) {

            // Sets the result to return to the component that called this Activity. The
            // result contains the new URI
            setResult(RESULT_OK, new Intent().setData(uri));
        } else {

            // Sends out an Intent to start an Activity that can handle ACTION_EDIT. The
            // Intent's data is the note ID URI. The effect is to call NoteEdit.
            startActivity(new Intent(Intent.ACTION_EDIT, uri));
        }
    }

    /**
     * 【扩展】从编辑器返回时刷新列表。
     *
     * <p>为什么需要这一步？
     * 原版依赖 provider 的 notifyChange + managedQuery 自动感知变化。但我们在搜索/
     * 筛选状态下改了 selection，最简单的做法是在页面重新可见时主动重查一次，
     * 保证“刚改完的笔记、刚改的分类、刚改的时间戳”立刻反映在列表里。
     */
    @Override
    protected void onResume() {
        super.onResume();
        if (mAdapter != null) {
            refreshList();
        }
    }

    /* ---------------- 【扩展】界面状态的保存与恢复 ---------------- */

    /**
     * 在 Activity 被销毁前保存界面状态。
     *
     * <p>触发场景有两种：一是用户切换主题后我们主动调用 {@link #recreate()}；
     * 二是系统因为旋转屏幕、内存不足回收等重建 Activity。
     * 两种情况都会走这里，所以只要实现了这个方法，切换主题后排序方式就不会被重置回默认值。
     */
    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString(STATE_SORT_ORDER, mSortOrder);
        outState.putInt(STATE_CATEGORY_FILTER, mCategoryFilter);
        outState.putString(STATE_QUERY, mQuery);
    }

    /**
     * 从 savedInstanceState 里把界面状态读回来。
     *
     * <p>首次启动时 savedInstanceState 为 null，各项保持字段的初始值即可
     * （排序=修改时间倒序、筛选=全部、搜索词=空）。
     */
    private void restoreUiState(Bundle savedInstanceState) {
        if (savedInstanceState == null) {
            return;
        }
        String sort = savedInstanceState.getString(STATE_SORT_ORDER);
        if (!TextUtils.isEmpty(sort)) {
            mSortOrder = sort;
        }
        mCategoryFilter = savedInstanceState.getInt(STATE_CATEGORY_FILTER, -1);
        String query = savedInstanceState.getString(STATE_QUERY);
        if (query != null) {
            mQuery = query;
        }
    }
}
