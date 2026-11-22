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

import static com.example.android.notepad.R.*;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.res.Resources;
import android.database.Cursor;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.util.AttributeSet;
import android.util.Log;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;

/**
 * This Activity handles "editing" a note, where editing is responding to
 * {@link Intent#ACTION_VIEW} (request to view data), edit a note
 * {@link Intent#ACTION_EDIT}, create a note {@link Intent#ACTION_INSERT}, or
 * create a new note from the current contents of the clipboard {@link Intent#ACTION_PASTE}.
 *
 * NOTE: Notice that the provider operations in this Activity are taking place on the UI thread.
 * This is not a good practice. It is only done here to make the code more readable. A real
 * application should use the {@link android.content.AsyncQueryHandler}
 * or {@link android.os.AsyncTask} object to perform operations asynchronously on a separate thread.
 */
public class NoteEditor extends Activity {
    // For logging and debugging purposes
    private static final String TAG = "NoteEditor";

    /*
     * Creates a projection that returns the note ID and the note contents.
     *
     * 【扩展】额外查询 created / modified / category 三列：
     *   - created、modified 用于在标题栏下方展示“创建于 / 修改于”；
     *   - category 用于记住当前笔记的分类，保存时不被覆盖。
     */
    private static final String[] PROJECTION =
        new String[] {
            NotePad.Notes._ID,                            // 0
            NotePad.Notes.COLUMN_NAME_TITLE,              // 1
            NotePad.Notes.COLUMN_NAME_NOTE,               // 2
            NotePad.Notes.COLUMN_NAME_CREATE_DATE,        // 3
            NotePad.Notes.COLUMN_NAME_MODIFICATION_DATE,  // 4
            NotePad.Notes.COLUMN_NAME_CATEGORY            // 5
    };

    private static final int COL_ID = 0;
    private static final int COL_TITLE = 1;
    private static final int COL_NOTE = 2;
    private static final int COL_CREATED = 3;
    private static final int COL_MODIFIED = 4;
    private static final int COL_CATEGORY = 5;

    // A label for the saved state of the activity
    private static final String ORIGINAL_CONTENT = "origContent";

    // This Activity can be started by more than one action. Each action is represented
    // as a "state" constant
    private static final int STATE_EDIT = 0;
    private static final int STATE_INSERT = 1;

    // Global mutable variables
    private int mState;
    private Uri mUri;
    private Cursor mCursor;
    private EditText mText;
    private String mOriginalContent;

    /* ---------------- 【扩展】新增成员 ---------------- */

    /** 当前笔记分类，默认“默认”分类 */
    private int mCategory = NotePad.Notes.CATEGORY_DEFAULT;

    /** 时间信息展示控件 */
    private TextView mTimeInfo;

    /** 分类快捷入口按钮 */
    private TextView mCategoryButton;

    /** 当前主题配色 */
    private ThemeHelper.Palette mPalette;

    /**
     * Defines a custom EditText View that draws lines between each line of text that is displayed.
     */
    public static class LinedEditText extends EditText {

        /**
         * 【扩展】兜底最小行数。
         *
         * <p>原版直接 getLineCount() 画横线，空白新笔记只有 1 行文字，
         * 于是屏幕上只出现 1 条横线，看起来像"横线没了"。
         * 这里至少画 24 行，保证新笔记本看起来就是一张横线纸。
         */
        private static final int MIN_LINES = 24;

        /** 【扩展】横线颜色，可由 Activity 根据主题替换 */
        private int mLineColor = 0x800000FF;

        private Rect mRect;
        private Paint mPaint;

        // This constructor is used by LayoutInflater
        public LinedEditText(Context context, AttributeSet attrs) {
            super(context, attrs);

            // Creates a Rect and a Paint object, and sets the style and color of the Paint object.
            mRect = new Rect();
            mPaint = new Paint();
            mPaint.setStyle(Paint.Style.STROKE);
            mPaint.setColor(mLineColor);
        }

        /** 【扩展】由 Activity 按当前主题设置横线颜色 */
        public void setLineColor(int color) {
            mLineColor = color;
            if (mPaint != null) {
                mPaint.setColor(color);
            }
            invalidate();
        }

        /**
         * This is called to draw the LinedEditText object
         * @param canvas The canvas on which the background is drawn.
         */
        @Override
        protected void onDraw(Canvas canvas) {

            // Gets the number of lines of text in the View.
            int count = getLineCount();

            // Gets the global Rect and Paint objects
            Rect r = mRect;
            Paint paint = mPaint;

            /*
             * 【扩展】行数取"文字行数"和 MIN_LINES 的较大值。
             *
             * EditText 在空白时 getLineCount() 返回 1，
             * 直接用 1 只能画出第一条横线，屏幕下半部分就是空白。
             */
            int lineHeight = getLineHeight();
            if (lineHeight <= 0) {
                lineHeight = 1;
            }
            int linesThatFit = getHeight() / lineHeight;
            int drawCount = Math.max(count, Math.max(MIN_LINES, linesThatFit));

            /*
             * Draws one line in the rectangle for every line of text in the EditText
             */
            for (int i = 0; i < drawCount; i++) {

                // Gets the baseline coordinates for the current line of text
                int baseline;
                if (i < count) {
                    baseline = getLineBounds(i, r);
                } else {
                    /*
                     * 超出实际文字的"虚拟行"：getLineBounds() 会失败（返回 0 并让 r 保持上一次的值），
                     * 所以按行高自己推算 baseline，并沿用最后一行的左右边界。
                     */
                    int lastBaseline = getLineBounds(count - 1, r);
                    baseline = lastBaseline + (i - count + 1) * lineHeight;
                    if (r.left == 0 && r.right == 0) {
                        r.set(getPaddingLeft(), 0, getWidth() - getPaddingRight(), 0);
                    }
                }

                /*
                 * Draws a line in the background from the left of the rectangle to the right,
                 * at a vertical position one dip below the baseline, using the "paint" object
                 * for details.
                 */
                canvas.drawLine(r.left, baseline + 1, r.right, baseline + 1, paint);
            }

            // Finishes up by calling the parent method
            super.onDraw(canvas);
        }
    }

    /**
     * This method is called by Android when the Activity is first started. From the incoming
     * Intent, it determines what kind of editing is desired, and then does it.
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        /*
         * Creates an Intent to use when the Activity object's result is sent back to the
         * caller.
         */
        final Intent intent = getIntent();

        /*
         *  Sets up for the edit, based on the action specified for the incoming Intent.
         */

        // Gets the action that triggered the intent filter for this Activity
        final String action = intent.getAction();

        // For an edit action:
        if (Intent.ACTION_EDIT.equals(action)) {

            // Sets the Activity state to EDIT, and gets the URI for the data to be edited.
            mState = STATE_EDIT;
            mUri = intent.getData();

            // For an insert or paste action:
        } else if (Intent.ACTION_INSERT.equals(action)
                || Intent.ACTION_PASTE.equals(action)) {

            // Sets the Activity state to INSERT, gets the general note URI, and inserts an
            // empty record in the provider
            mState = STATE_INSERT;
            mUri = getContentResolver().insert(intent.getData(), null);

            /*
             * If the attempt to insert the new note fails, shuts down this Activity. The
             * originating Activity receives back RESULT_CANCELED if it requested a result.
             * Logs that the insert failed.
             */
            if (mUri == null) {

                // Writes the log identifier, a message, and the URI that failed.
                Log.e(TAG, "Failed to insert new note into " + getIntent().getData());

                // Closes the activity.
                finish();
                return;
            }

            // Since the new entry was created, this sets the result to be returned
            // set the result to be returned.
            setResult(RESULT_OK, (new Intent()).setAction(mUri.toString()));

        // If the action was other than EDIT or INSERT:
        } else {

            // Logs an error that the action was not understood, finishes the Activity, and
            // returns RESULT_CANCELED to an originating Activity.
            Log.e(TAG, "Unknown action, exiting");
            finish();
            return;
        }

        /*
         * Using the URI passed in with the triggering Intent, gets the note or notes in
         * the provider.
         * Note: This is being done on the UI thread. It will block the thread until the query
         * completes. In a sample app, going against a simple provider based on a local database,
         * the block will be momentary, but in a real app you should use
         * android.content.AsyncQueryHandler or android.os.AsyncTask.
         *
         * 【重要改动】原版这里用的是 managedQuery()，本次改成 getContentResolver().query()。
         *
         * 为什么必须改？
         *   managedQuery 会把 Cursor 交给 Activity 托管，Activity 在 onStop() 时会自动
         *   关闭这个 Cursor；而本项目在 onResume() 里调用了 mCursor.requery()。
         *   在现代 Android（targetSdk 高版本）上，Activity 被重新启动时框架也会自己
         *   调一次 requery，此时 Cursor 已经被关掉，于是抛
         *   IllegalStateException: trying to requery an already closed cursor 而崩溃。
         *
         *   改成 getContentResolver().query() 后，Cursor 的生命周期由我们自己管，
         *   Activity 不再插手，requery 的时机就完全在可控范围内。
         *   代价是需要在 onDestroy() 里手动 close()，见 onDestroy()。
         */
        mCursor = getContentResolver().query(
            mUri,         // The URI that gets multiple notes from the provider.
            PROJECTION,   // A projection that returns the note ID and note content for each note.
            null,         // No "where" clause selection criteria.
            null,         // No "where" clause selection values.
            null          // Use the default sort order (modification date, descending)
        );

        // For a paste, initializes the data from clipboard.
        // (Must be done after mCursor is initialized.)
        if (Intent.ACTION_PASTE.equals(action)) {
            // Does the paste
            performPaste();
            // Switches the state to EDIT so the title can be modified.
            mState = STATE_EDIT;
        }

        // Sets the layout for this Activity. See res/layout/note_editor.xml
        setContentView(R.layout.note_editor);

        // Gets a handle to the EditText in the the layout.
        mText = (EditText) findViewById(R.id.note);

        // 【扩展】取出时间信息与分类入口这两个新增控件，并套用当前主题
        mTimeInfo = (TextView) findViewById(R.id.note_time_info);
        mCategoryButton = (TextView) findViewById(R.id.note_category_button);
        mPalette = ThemeHelper.getPalette(this);
        applyEditorTheme();

        /*
         * If this Activity had stopped previously, its state was written the ORIGINAL_CONTENT
         * location in the saved Instance state. This gets the state.
         */
        if (savedInstanceState != null) {
            mOriginalContent = savedInstanceState.getString(ORIGINAL_CONTENT);
        }
    }

    /**
     * 【扩展】把主题配色应用到编辑器界面。
     *
     * <p>编辑器原本用的是 {@code @android:style/Theme.Holo.Light}（在 Manifest 里指定），
     * 这里不改主题本身，只改“文字色 / 背景色”，做到与原版视觉风格一致的前提下换肤。
     */
    private void applyEditorTheme() {
        if (mPalette == null) {
            return;
        }
        View root = findViewById(R.id.editor_root);
        if (root != null) {
            root.setBackgroundColor(mPalette.background);
        }
        mText.setTextColor(mPalette.textPrimary);
        /*
         * 【注意】LinedEditText 的横线是在 onDraw() 里用 Paint 画的，
         * 属于"前景绘制"，只要背景是不透明色就一定会覆盖掉横线。
         *
         * 布局里原本写的是 @android:color/transparent，
         * 早期版本我在这里 setBackgroundColor(mPalette.background)，结果横线全消失。
         * 正确做法：背景保持透明，把底色交给父容器（editor_root）承担。
         */
        mText.setBackgroundColor(android.graphics.Color.TRANSPARENT);

        /*
         * 【扩展】把横线颜色也接到主题上。
         * 深色主题下原来的蓝色横线（0x800000FF）太扎眼，用主题的分隔线色更协调。
         */
        if (mText instanceof LinedEditText) {
            int lineColor = (mPalette.divider & 0x00FFFFFF) | 0x99000000;
            ((LinedEditText) mText).setLineColor(lineColor);
        }

        if (mTimeInfo != null) {
            mTimeInfo.setTextColor(mPalette.textSecondary);
        }

        /*
         * 【扩展】把系统 ActionBar 也染成主题顶栏色。
         *
         * 编辑器这一页保留了系统 ActionBar（保存/删除/更多三个按钮都在上面），
         * 没跟着主题走的话会是一条灰色白底栏，跟列表页的彩色顶栏风格割裂。
         * 这里只改背景色，标题文字在深色底上仍是白字，不用额外调。
         */
        android.app.ActionBar actionBar = getActionBar();
        if (actionBar != null) {
            actionBar.setBackgroundDrawable(
                    new android.graphics.drawable.ColorDrawable(mPalette.topBar));
        }
        /*
         * setStatusBarColor 只有在窗口"自己绘制系统栏背景"时才生效，
         * 否则状态栏仍是系统默认色（这里原本就是黑色）。
         * 加上这个 flag 之后状态栏才会跟顶栏连成一片。
         */
        getWindow().addFlags(
                android.view.WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        getWindow().setStatusBarColor(mPalette.topBar);

        if (mCategoryButton != null) {
            mCategoryButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showCategoryPicker();
                }
            });
        }
    }

    /**
     * 【扩展】编辑器里的分类选择。
     *
     * <p>与列表页长按菜单里的分类对话框是同一个数据操作，只是入口不同。
     * 这里选择的分类会立刻写入数据库，因为编辑器的 onPause 只负责写正文和标题，
     * 顺带写分类会与“撤销修改”逻辑冲突（撤销不该把分类也回滚）。
     */
    private void showCategoryPicker() {
        final String[] names = CategoryHelper.getAllNames();
        new AlertDialog.Builder(this)
                .setTitle(R.string.hint_category)
                .setSingleChoiceItems(names, mCategory,
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                mCategory = which;
                                ContentValues values = new ContentValues();
                                values.put(NotePad.Notes.COLUMN_NAME_CATEGORY, which);
                                getContentResolver().update(mUri, values, null, null);
                                updateCategoryButton();
                                dialog.dismiss();
                            }
                        })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 刷新编辑器顶部的分类按钮文字与颜色 */    private void updateCategoryButton() {
        if (mCategoryButton == null) {
            return;
        }
        int color = CategoryHelper.getLabelColor(this, mCategory);
        mCategoryButton.setText("分类：" + CategoryHelper.getName(mCategory));
        mCategoryButton.setTextColor(color);

        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(24);
        bg.setColor((color & 0x00FFFFFF) | 0x1A000000);
        mCategoryButton.setBackground(bg);
    }

    /**
     * 【扩展】刷新编辑器顶部的“创建于 / 修改于”信息。
     *
     * <p>这里能直接显示完整时间，是因为 PROJECTION 里已经查了 created 和 modified。
     * 注意创建时间从插入后就不再变化，修改时间每次保存都会被 updateNote() 刷新。
     */
    private void updateTimeInfo() {
        if (mTimeInfo == null || mCursor == null) {
            return;
        }
        long created = mCursor.getLong(COL_CREATED);
        long modified = mCursor.getLong(COL_MODIFIED);
        mTimeInfo.setText("创建于 " + TimeUtils.formatFullTime(created)
                + "    ·    修改于 " + TimeUtils.formatFullTime(modified));
    }

    /**
     * 【扩展】自动生成标题：正文非空且标题仍为空/占位时，取正文第一行前 30 个字符当标题。
     *
     * <p>为什么不在 onPause 里做？
     * 因为 onPause 时用户可能只是"按返回直接退出"，还没来得及看到列表效果；
     * 而 onResume 时正文已经 load 出来了，这里做一次判断即可，代价极小（一次 update）。
     *
     * <p>为什么不更新 modified？
     * 因为标题是从正文推导出来的"派生值"，不代表用户真的修改过内容，
     * 更新 modified 会让排序错乱（刚打开笔记就跳到列表最前面）。
     */
    private void applyAutoTitle() {
        if (mCursor == null || mText == null || mUri == null) {
            return;
        }
        String title = mCursor.getString(COL_TITLE);
        String content = mText.getText().toString();

        boolean titleIsPlaceholder = title == null
                || title.trim().length() == 0
                || "<Untitled>".equals(title.trim());
        if (!titleIsPlaceholder || content.trim().length() == 0) {
            return;
        }

        String firstLine = content.trim().split("\\r?\\n")[0].trim().replaceAll("\\s+", " ");
        if (firstLine.length() == 0) {
            return;
        }
        String newTitle = firstLine.length() > 30 ? firstLine.substring(0, 30) : firstLine;

        ContentValues values = new ContentValues();
        values.put(NotePad.Notes.COLUMN_NAME_TITLE, newTitle);
        getContentResolver().update(mUri, values, null, null);

        Log.d(TAG, "auto title generated: " + newTitle);
    }

    /**
     * This method is called when the Activity is about to come to the foreground. This happens
     * when the Activity comes to the top of the task stack, OR when it is first starting.
     *
     * Moves to the first note in the list, sets an appropriate title for the action chosen by
     * the user, puts the note contents into the TextView, and saves the original text as a
     * backup.
     */
    @Override
    protected void onResume() {
        super.onResume();

        /*
         * mCursor is initialized, since onCreate() always precedes onResume for any running
         * process. This tests that it's not null, since it should always contain data.
         */
        if (mCursor != null) {
            // Requery in case something changed while paused (such as the title)
            mCursor.requery();

            /* Moves to the first record. Always call moveToFirst() before accessing data in
             * a Cursor for the first time. The semantics of using a Cursor are that when it is
             * created, its internal index is pointing to a "place" immediately before the first
             * record.
             */
            mCursor.moveToFirst();

            // Modifies the window title for the Activity according to the current Activity state.
            if (mState == STATE_EDIT) {
                // Set the title of the Activity to include the note title
                int colTitleIndex = mCursor.getColumnIndex(NotePad.Notes.COLUMN_NAME_TITLE);
                String title = mCursor.getString(colTitleIndex);
                Resources res = getResources();
                String text = String.format(res.getString(R.string.title_edit), title);
                setTitle(text);
            // Sets the title to "create" for inserts
            } else if (mState == STATE_INSERT) {
                setTitle(getText(R.string.title_create));
            }

            /*
             * onResume() may have been called after the Activity lost focus (was paused).
             * The user was either editing or creating a note when the Activity paused.
             * The Activity should re-display the text that had been retrieved previously, but
             * it should not move the cursor. This helps the user to continue editing or entering.
             */

            // Gets the note text from the Cursor and puts it in the TextView, but doesn't change
            // the text cursor's position.
            int colNoteIndex = mCursor.getColumnIndex(NotePad.Notes.COLUMN_NAME_NOTE);
            String note = mCursor.getString(colNoteIndex);
            mText.setTextKeepState(note);

            // Stores the original note text, to allow the user to revert changes.
            if (mOriginalContent == null) {
                mOriginalContent = note;
            }

            /*
             * 【扩展】把当前笔记的分类读进来，并刷新顶部时间信息。
             * 注意必须在 moveToFirst() 之后读取，否则下标无效。
             */
            mCategory = mCursor.getInt(COL_CATEGORY);
            updateCategoryButton();
            updateTimeInfo();

            /*
             * 【扩展·改进】自动生成标题（原版没有这个能力）。
             *
             * 原版 NotePad 如果不手动点"编辑标题"，列表里永远显示 <Untitled>。
             * 这里在 onResume 时判断：如果标题还是空默认值，就用正文第一行前 30 个字符兜底。
             *
             * 注意必须满足两个条件才写库：
             *   1) 标题为空或 <Untitled>；
             *   2) 正文非空（否则新建的空笔记会立刻被塞一个假标题）。
             * 只写 title 字段，不碰 modified，避免"刚打开就被算作修改过"。
             */
            applyAutoTitle();

        /*
         * Something is wrong. The Cursor should always contain data. Report an error in the
         * note.
         */
        } else {
            setTitle(getText(R.string.error_title));
            mText.setText(getText(R.string.error_message));
        }
    }

    /**
     * This method is called when an Activity loses focus during its normal operation, and is then
     * later on killed. The Activity has a chance to save its state so that the system can restore
     * it.
     *
     * Notice that this method isn't a normal part of the Activity lifecycle. It won't be called
     * if the user simply navigates away from the Activity.
     */
    @Override
    protected void onSaveInstanceState(Bundle outState) {
        // Save away the original text, so we still have it if the activity
        // needs to be killed while paused.
        outState.putString(ORIGINAL_CONTENT, mOriginalContent);
    }

    /**
     * 【扩展】释放自己管理的 Cursor。
     *
     * <p>因为 Cursor 已经从 managedQuery() 改成 getContentResolver().query()，
     * Activity 不再帮忙关闭它，所以必须在这里手动 close，否则会内存泄漏
     * （CursorWindow 持有 native 内存，退出后不会立刻回收）。
     */
    @Override
    protected void onDestroy() {
        if (mCursor != null) {
            mCursor.close();
            mCursor = null;
        }
        super.onDestroy();
    }

    /**
     * This method is called when the Activity loses focus.
     *
     * For Activity objects that edit information, onPause() may be the one place where changes are
     * saved. The Android application model is predicated on the idea that "save" and "exit" aren't
     * required actions. When users navigate away from an Activity, they shouldn't have to go back
     * to it to complete their work. The act of going away should save everything and leave the
     * Activity in a state where Android can destroy it if necessary.
     *
     * If the user hasn't done anything, then this deletes or clears out the note, otherwise it
     * writes the user's work to the provider.
     */
    @Override
    protected void onPause() {
        super.onPause();

        /*
         * Tests to see that the query operation didn't fail (see onCreate()). The Cursor object
         * will exist, even if no records were returned, unless the query failed because of some
         * exception or error.
         *
         */
        if (mCursor != null) {

            // Get the current note text.
            String text = mText.getText().toString();
            int length = text.length();

            /*
             * If the Activity is in the midst of finishing and there is no text in the current
             * note, returns a result of CANCELED to the caller, and deletes the note. This is done
             * even if the note was being edited, the assumption being that the user wanted to
             * "clear out" (delete) the note.
             */
            if (isFinishing() && (length == 0)) {
                setResult(RESULT_CANCELED);
                deleteNote();

                /*
                 * Writes the edits to the provider. The note has been edited if an existing note was
                 * retrieved into the editor *or* if a new note was inserted. In the latter case,
                 * onCreate() inserted a new empty note into the provider, and it is this new note
                 * that is being edited.
                 */
            } else if (mState == STATE_EDIT) {
                // Creates a map to contain the new values for the columns
                updateNote(text, null);
            } else if (mState == STATE_INSERT) {
                updateNote(text, text);
                mState = STATE_EDIT;
          }
        }
    }

    /**
     * This method is called when the user clicks the device's Menu button the first time for
     * this Activity. Android passes in a Menu object that is populated with items.
     *
     * Builds the menus for editing and inserting, and adds in alternative actions that
     * registered themselves to handle the MIME types for this application.
     *
     * @param menu A Menu object to which items should be added.
     * @return True to display the menu.
     */
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate menu from XML resource
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.editor_options_menu, menu);

        // Only add extra menu items for a saved note 
        if (mState == STATE_EDIT) {
            // Append to the
            // menu items for any other activities that can do stuff with it
            // as well.  This does a query on the system for any activities that
            // implement the ALTERNATIVE_ACTION for our data, adding a menu item
            // for each one that is found.
            Intent intent = new Intent(null, mUri);
            intent.addCategory(Intent.CATEGORY_ALTERNATIVE);
            menu.addIntentOptions(Menu.CATEGORY_ALTERNATIVE, 0, 0,
                    new ComponentName(this, NoteEditor.class), null, intent, 0, null);
        }

        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        // Check if note has changed and enable/disable the revert option
        int colNoteIndex = mCursor.getColumnIndex(NotePad.Notes.COLUMN_NAME_NOTE);
        String savedNote = mCursor.getString(colNoteIndex);
        String currentNote = mText.getText().toString();
        if (savedNote.equals(currentNote)) {
            menu.findItem(R.id.menu_revert).setVisible(false);
        } else {
            menu.findItem(R.id.menu_revert).setVisible(true);
        }
        return super.onPrepareOptionsMenu(menu);
    }

    /**
     * This method is called when a menu item is selected. Android passes in the selected item.
     * The switch statement in this method calls the appropriate method to perform the action the
     * user chose.
     *
     * @param item The selected MenuItem
     * @return True to indicate that the item was processed, and no further work is necessary. False
     * to proceed to further processing as indicated in the MenuItem object.
     */
    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle all of the possible menu actions.
        int id = item.getItemId();
        if(id== R.id.menu_save) {
            String text = mText.getText().toString();
            updateNote(text, null);
            finish();
        } else if (id == R.id.menu_delete) {
            deleteNote();
            finish();
        } else if (id == R.id.menu_revert) {
            cancelNote();
        }
        return super.onOptionsItemSelected(item);
    }

//BEGIN_INCLUDE(paste)
    /**
     * A helper method that replaces the note's data with the contents of the clipboard.
     */
    private final void performPaste() {

        // Gets a handle to the Clipboard Manager
        ClipboardManager clipboard = (ClipboardManager)
                getSystemService(Context.CLIPBOARD_SERVICE);

        // Gets a content resolver instance
        ContentResolver cr = getContentResolver();

        // Gets the clipboard data from the clipboard
        ClipData clip = clipboard.getPrimaryClip();
        if (clip != null) {

            String text=null;
            String title=null;

            // Gets the first item from the clipboard data
            ClipData.Item item = clip.getItemAt(0);

            // Tries to get the item's contents as a URI pointing to a note
            Uri uri = item.getUri();

            // Tests to see that the item actually is an URI, and that the URI
            // is a content URI pointing to a provider whose MIME type is the same
            // as the MIME type supported by the Note pad provider.
            if (uri != null && NotePad.Notes.CONTENT_ITEM_TYPE.equals(cr.getType(uri))) {

                // The clipboard holds a reference to data with a note MIME type. This copies it.
                Cursor orig = cr.query(
                        uri,            // URI for the content provider
                        PROJECTION,     // Get the columns referred to in the projection
                        null,           // No selection variables
                        null,           // No selection variables, so no criteria are needed
                        null            // Use the default sort order
                );

                // If the Cursor is not null, and it contains at least one record
                // (moveToFirst() returns true), then this gets the note data from it.
                if (orig != null) {
                    if (orig.moveToFirst()) {
                        int colNoteIndex = mCursor.getColumnIndex(NotePad.Notes.COLUMN_NAME_NOTE);
                        int colTitleIndex = mCursor.getColumnIndex(NotePad.Notes.COLUMN_NAME_TITLE);
                        text = orig.getString(colNoteIndex);
                        title = orig.getString(colTitleIndex);
                    }

                    // Closes the cursor.
                    orig.close();
                }
            }

            // If the contents of the clipboard wasn't a reference to a note, then
            // this converts whatever it is to text.
            if (text == null) {
                text = item.coerceToText(this).toString();
            }

            // Updates the current note with the retrieved title and text.
            updateNote(text, title);
        }
    }
//END_INCLUDE(paste)

    /**
     * Replaces the current note contents with the text and title provided as arguments.
     * @param text The new note contents to use.
     * @param title The new note title to use
     */
    private final void updateNote(String text, String title) {

        // Sets up a map to contain values to be updated in the provider.
        ContentValues values = new ContentValues();
        values.put(NotePad.Notes.COLUMN_NAME_MODIFICATION_DATE, System.currentTimeMillis());

        // If the action is to insert a new note, this creates an initial title for it.
        if (mState == STATE_INSERT) {

            // If no title was provided as an argument, create one from the note text.
            if (title == null) {
  
                // Get the note's length
                int length = text.length();

                // Sets the title by getting a substring of the text that is 31 characters long
                // or the number of characters in the note plus one, whichever is smaller.
                //
                // 【扩展】原版直接取前 30 个字符，遇到正文以换行开头会得到换行符，
                // 列表里就显示成空标题。这里做两点修正：
                //   1) 先去掉首尾空白并压缩连续换行，再截取；
                //   2) 若正文本身为空，使用“无标题笔记”兜底。
                String normalized = text == null ? "" : text.trim().replaceAll("\\s+", " ");
                if (normalized.length() == 0) {
                    title = "无标题笔记";
                } else {
                    title = normalized.substring(0, Math.min(30, normalized.length()));

                    // If the resulting length is more than 30 characters, chops off any
                    // trailing spaces
                    if (normalized.length() > 30) {
                        int lastSpace = title.lastIndexOf(' ');
                        if (lastSpace > 0) {
                            title = title.substring(0, lastSpace);
                        }
                    }
                }
            }
            // In the values map, sets the value of the title
            values.put(NotePad.Notes.COLUMN_NAME_TITLE, title);
        } else if (title != null) {
            // In the values map, sets the value of the title
            values.put(NotePad.Notes.COLUMN_NAME_TITLE, title);
        }

        // This puts the desired notes text into the map.
        values.put(NotePad.Notes.COLUMN_NAME_NOTE, text);

        /*
         * 【扩展】保存时一并写入分类。
         *
         * 为什么每次保存都写？
         * 因为 update() 用的是 ContentValues 局部更新，不写的列会保持原值；
         * 但如果在编辑器里改过分类（showCategoryPicker 已立即写库），
         * 这里再写一次 mCategory 也不会产生副作用，可保证内存与数据库一致。
         */
        values.put(NotePad.Notes.COLUMN_NAME_CATEGORY, mCategory);

        /*
         * Updates the provider with the new values in the map. The ListView is updated
         * automatically. The provider sets this up by setting the notification URI for
         * query Cursor objects to the incoming URI. The content resolver is thus
         * automatically notified when the Cursor for the URI changes, and the UI is
         * updated.
         * Note: This is being done on the UI thread. It will block the thread until the
         * update completes. In a sample app, going against a simple provider based on a
         * local database, the block will be momentary, but in a real app you should use
         * android.content.AsyncQueryHandler or android.os.AsyncTask.
         */
        getContentResolver().update(
                mUri,    // The URI for the record to update.
                values,  // The map of column names and new values to apply to them.
                null,    // No selection criteria are used, so no where columns are necessary.
                null     // No where columns are used, so no where arguments are necessary.
            );


    }

    /**
     * This helper method cancels the work done on a note.  It deletes the note if it was
     * newly created, or reverts to the original text of the note i
     */
    private final void cancelNote() {
        if (mCursor != null) {
            if (mState == STATE_EDIT) {
                // Put the original note text back into the database
                mCursor.close();
                mCursor = null;
                ContentValues values = new ContentValues();
                values.put(NotePad.Notes.COLUMN_NAME_NOTE, mOriginalContent);
                getContentResolver().update(mUri, values, null, null);
            } else if (mState == STATE_INSERT) {
                // We inserted an empty note, make sure to delete it
                deleteNote();
            }
        }
        setResult(RESULT_CANCELED);
        finish();
    }

    /**
     * Take care of deleting a note.  Simply deletes the entry.
     */
    private final void deleteNote() {
        if (mCursor != null) {
            mCursor.close();
            mCursor = null;
            getContentResolver().delete(mUri, null, null);
            mText.setText("");
        }
    }
}
