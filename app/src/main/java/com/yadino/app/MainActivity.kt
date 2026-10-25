package com.yadino.app

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.app.Dialog
import android.app.PendingIntent
import android.app.TimePickerDialog
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.text.InputType
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.view.animation.DecelerateInterpolator
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.android.material.card.MaterialCardView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.UUID

private const val TAG = "Yadino"
private const val STORAGE_NAME = "yadino_secure_storage"
private const val NOTES_KEY = "notes_json"
private const val PIN_KEY = "pin_hash"
private const val CHANNEL_ID = "yadino_reminders"
private const val CHANNEL_ID_SILENT = "yadino_reminders_silent"
private const val AUDIO_PERMISSION_CODE = 7002
private const val NOTIFICATION_PERMISSION_CODE = 7003

private const val COLOR_BG_TOP = "#1A1A1A"
private const val COLOR_BG_BOTTOM = "#050505"
private const val COLOR_CARD = "#181818"
private const val COLOR_GOLD = "#D4AF37"
private const val COLOR_GOLD_DARK = "#AA8B2B"
private const val COLOR_TEXT_PRIMARY = "#FFFFFF"
private const val COLOR_TEXT_SECONDARY = "#A0A0A0"

private const val REMINDER_NONE_TEXT = "یادآور تنظیم نشده است"

private val persianMonths = arrayOf(
    "فروردین", "اردیبهشت", "خرداد", "تیر", "مرداد", "شهریور",
    "مهر", "آبان", "آذر", "دی", "بهمن", "اسفند"
)

data class Task(
    val id: String = UUID.randomUUID().toString(),
    var text: String,
    var done: Boolean = false
)

data class Note(
    val id: String = UUID.randomUUID().toString(),
    var title: String,
    var body: String,
    var category: String,
    var isPrivate: Boolean = false,
    var colorHex: String = "",
    val imagePaths: MutableList<String> = mutableListOf(),
    val tasks: MutableList<Task> = mutableListOf(),
    var reminderAt: Long = 0L,
    var reminderEnabled: Boolean = false,
    var repeatMode: String = "none",
    var repeatCount: Int = 0,
    var repeatIntervalMinutes: Int = 60,
    var ringtoneUri: String = "",
    var reminderFiredCount: Int = 0
)

class MainActivity : AppCompatActivity() {

    private val categories = listOf("همه دسته‌ها", "عمومی", "کار", "شخصی", "خرید", "درس", "ایده‌ها")
    private val notes = mutableListOf<Note>()

    private lateinit var titleInput: EditText
    private lateinit var bodyInput: EditText
    private lateinit var categorySpinner: Spinner
    private lateinit var privateCheckBox: CheckBox
    private lateinit var taskInput: EditText
    private lateinit var notesRecyclerView: RecyclerView
    private lateinit var noteAdapter: NoteAdapter
    private lateinit var filterSpinner: Spinner
    private lateinit var reminderSummary: TextView
    private lateinit var formContainer: LinearLayout
    private lateinit var mainScroll: ScrollView
    private lateinit var securePrefs: android.content.SharedPreferences
    private lateinit var draftImagesContainer: LinearLayout

    private lateinit var fab: FloatingActionButton
    private lateinit var micStatusText: TextView
    private lateinit var micButton: Button
    private var blinkAnimation: AlphaAnimation? = null

    private var editingNoteId: String? = null
    private var selectedReminderAt: Long = 0L
    private var selectedReminderEnabled = false
    private var selectedRepeatMode = "none"
    private var selectedRepeatCount = 0
    private var selectedRepeatInterval = 60
    private var selectedRingtoneUri = ""
    private val currentDraftImagePaths = mutableListOf<String>()

    private var pendingSnoozeNoteId: String? = null

    private var speechRecognizer: SpeechRecognizer? = null
    private var continuousSpeech = false
    private var speechTarget: EditText? = null
    private var isListening = false
    private var voiceBaseText = ""
    private var isOfflineMode = false
    private val handler = Handler(Looper.getMainLooper())
    private val restartRunnable = Runnable { if (continuousSpeech) launchRecognizer() }

    private val ringtonePickerLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uri: Uri? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                result.data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                result.data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            }
            selectedRingtoneUri = if (uri == null) "silent" else uri.toString()
        }
        updateReminderSummary()
    }

    private val imagePickerLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetMultipleContents()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            for (uri in uris) {
                copyUriToInternalStorage(uri)
            }
            refreshDraftImages()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        if (intent?.action == "SHOW_SNOOZE_DIALOG") {
            pendingSnoozeNoteId = intent.getStringExtra("note_id")
        }

        var keepSplash = true
        val splashScreen = installSplashScreen()
        splashScreen.setKeepOnScreenCondition { keepSplash }
        Handler(Looper.getMainLooper()).postDelayed({ keepSplash = false }, 1500)

        super.onCreate(savedInstanceState)
        try {
            initializeSecureStorage()
            loadNotes()
            createNotificationChannel()
            buildScreen()
            renderNotes()
            requestNotificationPermissionIfNeeded()
        } catch (e: Exception) {
            Log.e(TAG, "Startup failed", e)
            Toast.makeText(this, "خطا در راه‌اندازی", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == "SHOW_SNOOZE_DIALOG") {
            val id = intent.getStringExtra("note_id")
            val note = notes.find { it.id == id }
            if (note != null) showSnoozeDialog(note)
        }
    }

    private fun initializeSecureStorage() {
        val masterKey = MasterKey.Builder(this).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        securePrefs = EncryptedSharedPreferences.create(
            this, STORAGE_NAME, masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    private fun copyUriToInternalStorage(uri: Uri) {
        try {
            val inputStream: InputStream? = contentResolver.openInputStream(uri)
            val fileName = "img_${UUID.randomUUID()}.jpg"
            val file = File(filesDir, fileName)
            val outputStream = FileOutputStream(file)
            inputStream?.copyTo(outputStream)
            inputStream?.close()
            outputStream.close()
            currentDraftImagePaths.add(file.absolutePath)
        } catch (e: Exception) {
            Log.e(TAG, "Error copying image", e)
        }
    }

    private fun pasteImageFromClipboard() {
        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            if (!clipboard.hasPrimaryClip()) {
                Toast.makeText(this, "حافظه خالی است.", Toast.LENGTH_SHORT).show()
                return
            }

            val clip = clipboard.primaryClip
            if (clip != null && clip.itemCount > 0) {
                val item = clip.getItemAt(0)
                if (item.uri != null && clip.description.hasMimeType("image/*")) {
                    copyUriToInternalStorage(item.uri)
                    refreshDraftImages()
                    Toast.makeText(this, "عکس پیست شد.", Toast.LENGTH_SHORT).show()
                    return
                }
                
                if (item.text != null) {
                    val text = item.text.toString()
                    if (text.startsWith("content://") || text.startsWith("file://")) {
                        copyUriToInternalStorage(Uri.parse(text))
                        refreshDraftImages()
                        Toast.makeText(this, "عکس پیست شد.", Toast.LENGTH_SHORT).show()
                        return
                    }
                }
            }
            Toast.makeText(this, "عکسی در حافظه یافت نشد.", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e(TAG, "Paste failed", e)
            Toast.makeText(this, "خطا در پیست کردن عکس", Toast.LENGTH_SHORT).show()
        }
    }

    private fun buildScreen() {
        val rootBg = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(Color.parseColor(COLOR_BG_TOP), Color.parseColor(COLOR_BG_BOTTOM))
        )

        val root = FrameLayout(this).apply {
            background = rootBg
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }

        mainScroll = ScrollView(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 60, 40, 450)
        }

        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            weightSum = 3f
        }

        // 🔧 سایز عنوان از 48 به 38 کوچیک شد
        val titleTv = TextView(this).apply {
            text = "یادینو"
            textSize = 38f
            gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
            letterSpacing = 0.05f
            setShadowLayer(15f, 0f, 0f, Color.parseColor("#80D4AF37"))
        }
        titleTv.post {
            val paint = titleTv.paint
            val width = paint.measureText(titleTv.text.toString())
            val shader = android.graphics.LinearGradient(
                0f, 0f, width, titleTv.textSize,
                intArrayOf(Color.parseColor("#F3E5AB"), Color.parseColor("#D4AF37"), Color.parseColor("#AA8B2B")),
                null, android.graphics.Shader.TileMode.CLAMP
            )
            paint.shader = shader
            titleTv.invalidate()
        }
        
        headerRow.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        headerRow.addView(titleTv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        
        val settingsBtn = TextView(this).apply {
            text = "⚙️"
            textSize = 32f
            gravity = Gravity.CENTER
            setOnClickListener { showSettingsDialog() }
        }
        headerRow.addView(settingsBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        
        content.addView(headerRow)
        content.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(1, 30) })

        formContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }

        fun styleInput(editText: EditText) {
            editText.setTextColor(Color.parseColor(COLOR_TEXT_PRIMARY))
            editText.setHintTextColor(Color.parseColor(COLOR_TEXT_SECONDARY))

            val density = resources.displayMetrics.density
            val normalInset = (2 * density).toInt()
            val focusedInset = (4 * density).toInt()

            val normalGold = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.parseColor("#AA8B2B"), Color.parseColor("#D4AF37"), Color.parseColor("#AA8B2B"))
            ).apply { cornerRadius = 40f }
            val normalInner = GradientDrawable().apply { setColor(Color.parseColor(COLOR_CARD)); cornerRadius = 38f }
            val normalLayer = LayerDrawable(arrayOf(normalGold, normalInner)).apply { setLayerInset(1, normalInset, normalInset, normalInset, normalInset) }

            val focusedGold = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.parseColor("#F3E5AB"), Color.parseColor("#D4AF37"), Color.parseColor("#AA8B2B"), Color.parseColor("#F3E5AB"))
            ).apply { cornerRadius = 40f }
            val focusedInner = GradientDrawable().apply { setColor(Color.parseColor("#202020")); cornerRadius = 36f }
            val focusedLayer = LayerDrawable(arrayOf(focusedGold, focusedInner)).apply { setLayerInset(1, focusedInset, focusedInset, focusedInset, focusedInset) }

            val rippleColor = ColorStateList.valueOf(Color.parseColor("#33D4AF37"))
            val normalRipple = RippleDrawable(rippleColor, normalLayer, null)
            val focusedRipple = RippleDrawable(rippleColor, focusedLayer, null)

            val states = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), focusedRipple)
                addState(intArrayOf(), normalRipple)
            }

            editText.background = states
            editText.setPadding(45, 40, 45, 40)
            editText.textSize = 16f
        }

        titleInput = EditText(this).apply { hint = "عنوان یادداشت" }
        styleInput(titleInput)
        titleInput.setOnFocusChangeListener { _, hasFocus -> if (hasFocus) speechTarget = titleInput }
        formContainer.addView(titleInput, matchWidth())

        bodyInput = EditText(this).apply { hint = "متن یادداشت"; minLines = 3; gravity = Gravity.TOP or Gravity.RIGHT }
        styleInput(bodyInput)
        bodyInput.setOnFocusChangeListener { _, hasFocus -> if (hasFocus) speechTarget = bodyInput }
        formContainer.addView(bodyInput, matchWidth())

        categorySpinner = Spinner(this)
        categorySpinner.adapter = spinnerAdapter(categories.drop(1))
        formContainer.addView(categorySpinner, matchWidth())

        privateCheckBox = CheckBox(this).apply { 
            text = "یادداشت خصوصی باشد"
            setTextColor(Color.parseColor(COLOR_TEXT_PRIMARY))
            buttonTintList = ColorStateList.valueOf(Color.parseColor(COLOR_GOLD))
        }
        formContainer.addView(privateCheckBox)

        taskInput = EditText(this).apply { hint = "کارها (هر خط یک کار)"; minLines = 2; gravity = Gravity.TOP or Gravity.RIGHT }
        styleInput(taskInput)
        taskInput.setOnFocusChangeListener { _, hasFocus -> if (hasFocus) speechTarget = taskInput }
        formContainer.addView(taskInput, matchWidth())

        fun createGoldButton(text: String, onClick: () -> Unit): Button {
            return Button(this).apply {
                this.text = text
                setTextColor(Color.BLACK)
                textSize = 16f
                typeface = Typeface.DEFAULT_BOLD
                
                val gradient = GradientDrawable(
                    GradientDrawable.Orientation.LEFT_RIGHT, 
                    intArrayOf(Color.parseColor(COLOR_GOLD), Color.parseColor(COLOR_GOLD_DARK))
                ).apply { cornerRadius = 100f }
                
                val ripple = RippleDrawable(ColorStateList.valueOf(Color.parseColor("#55000000")), gradient, null)
                background = ripple
                
                setOnClickListener { view ->
                    view.animate().scaleX(0.95f).scaleY(0.95f).setDuration(100).withEndAction {
                        view.animate().scaleX(1f).scaleY(1f).setDuration(100).start()
                        onClick()
                    }.start()
                }
            }
        }

        micButton = createGoldButton("🎤 شروع ضبط صدا") { toggleVoiceInput(false) }
        formContainer.addView(micButton, matchWidth())

        micStatusText = TextView(this).apply {
            text = ""
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor(COLOR_GOLD))
            setPadding(0, 10, 0, 20)
            visibility = View.GONE
        }
        formContainer.addView(micStatusText, matchWidth())

        formContainer.addView(createGoldButton("📷 افزودن عکس‌ها") { 
            imagePickerLauncher.launch("image/*") 
        }, matchWidth())

        formContainer.addView(createGoldButton("📋 پیست عکس از حافظه") {
            pasteImageFromClipboard()
        }, matchWidth())

        val draftImagesScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setPadding(0, 10, 0, 20)
        }
        draftImagesContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        draftImagesScroll.addView(draftImagesContainer)
        formContainer.addView(draftImagesScroll)

        formContainer.addView(createGoldButton("تنظیم یادآور") { configureReminder() }, matchWidth())
        formContainer.addView(createGoldButton("🤖 دستیار هوشمند") { 
            Toast.makeText(this, "قابلیت هوش مصنوعی به زودی فعال می‌شود...", Toast.LENGTH_SHORT).show() 
        }, matchWidth())

        reminderSummary = TextView(this).apply { 
            text = REMINDER_NONE_TEXT
            setTextColor(Color.parseColor(COLOR_TEXT_SECONDARY))
            gravity = Gravity.CENTER
            setPadding(0, 20, 0, 20)
        }
        formContainer.addView(reminderSummary, matchWidth())

        formContainer.addView(createGoldButton("ذخیره یادداشت") { saveCurrentNote() }, matchWidth())
        formContainer.addView(Button(this).apply {
            text = "انصراف"
            setTextColor(Color.parseColor(COLOR_TEXT_SECONDARY))
            background = null
            setOnClickListener { 
                formContainer.visibility = View.GONE
                fab.show()
                clearForm() 
            }
        }, matchWidth())

        content.addView(formContainer)

        filterSpinner = Spinner(this)
        filterSpinner.adapter = spinnerAdapter(categories)
        filterSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) { renderNotes() }
        }
        content.addView(filterSpinner, matchWidth())

        notesRecyclerView = RecyclerView(this).apply {
            layoutManager = GridLayoutManager(this@MainActivity, 2)
            setHasFixedSize(false)
            isNestedScrollingEnabled = false
        }
        
        noteAdapter = NoteAdapter(
            items = notes,
            onClick = { note -> showNoteDetails(note) },
            onLongClick = { note -> showNoteOptionsMenu(note) }
        )
        notesRecyclerView.adapter = noteAdapter
        content.addView(notesRecyclerView, matchWidth())

        mainScroll.addView(content)
        root.addView(mainScroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        fab = FloatingActionButton(this).apply {
            setImageResource(android.R.drawable.ic_input_add)
            imageTintList = ColorStateList.valueOf(Color.parseColor(COLOR_BG_BOTTOM)) 
            backgroundTintList = ColorStateList.valueOf(Color.parseColor(COLOR_GOLD))
            compatElevation = 25f
            
            setOnClickListener { view ->
                view.animate().rotation(90f).setDuration(200).withEndAction {
                    formContainer.visibility = View.VISIBLE
                    fab.hide()
                    mainScroll.post { mainScroll.fullScroll(View.FOCUS_UP) }
                    view.animate().rotation(0f).setDuration(0).start()
                }.start()
            }
        }
        val fabParams = FrameLayout.LayoutParams(170, 170).apply { 
            gravity = Gravity.BOTTOM or Gravity.START
            setMargins(50, 50, 50, 100) 
        }
        root.addView(fab, fabParams)

        setContentView(root)
    }

    private fun refreshDraftImages() {
        if (!::draftImagesContainer.isInitialized) return
        draftImagesContainer.removeAllViews()

        for (path in currentDraftImagePaths) {
            val itemLayout = FrameLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(300, 300).apply { marginEnd = 20 }
            }

            val imgView = ImageView(this).apply {
                layoutParams = FrameLayout.LayoutParams(300, 300)
                scaleType = ImageView.ScaleType.CENTER_CROP
                val bmp = loadBitmap(path, 300, 300)
                if (bmp != null) setImageBitmap(bmp)
                background = GradientDrawable().apply { cornerRadius = 20f; setColor(Color.DKGRAY) }
            }
            itemLayout.addView(imgView)

            val removeBtn = TextView(this).apply {
                text = "❌"
                textSize = 16f
                gravity = Gravity.CENTER
                setBackgroundColor(Color.parseColor("#80000000"))
                layoutParams = FrameLayout.LayoutParams(60, 60).apply {
                    gravity = Gravity.TOP or Gravity.RIGHT
                }
                setOnClickListener {
                    currentDraftImagePaths.remove(path)
                    refreshDraftImages()
                }
            }
            itemLayout.addView(removeBtn)

            draftImagesContainer.addView(itemLayout)
        }
    }

    private fun showSettingsDialog() {
        val options = arrayOf("🔐 تغییر رمز عبور", "⭐️ امتیاز به برنامه در مایکت")
        showCustomListDialog("تنظیمات", options) { which ->
            if (which == 0) {
                showSetPinDialog { Toast.makeText(this, "رمز عبور با موفقیت تغییر کرد", Toast.LENGTH_SHORT).show() }
            } else {
                try {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://myket.ir/app/com.yadino.app"))
                    startActivity(intent)
                } catch (e: Exception) {
                    Toast.makeText(this, "مایکت یافت نشد", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun matchWidth(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 24 }

    private fun spinnerAdapter(items: List<String>): ArrayAdapter<String> {
        return object : ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, items) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = super.getView(position, convertView, parent) as TextView
                view.setTextColor(Color.parseColor(COLOR_TEXT_PRIMARY))
                view.textSize = 16f
                return view
            }
        }.also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
    }

    private fun showForm() { 
        formContainer.visibility = View.VISIBLE
        fab.hide()
        mainScroll.post { mainScroll.fullScroll(View.FOCUS_UP) } 
    }

    private fun renderNotes() {
        val selectedCategory = if (::filterSpinner.isInitialized) filterSpinner.selectedItem?.toString() ?: "همه دسته‌ها" else "همه دسته‌ها"
        val visibleNotes = notes.filter { selectedCategory == "همه دسته‌ها" || it.category == selectedCategory }
        noteAdapter.updateData(visibleNotes)
    }

    private fun loadBitmap(filePath: String, reqWidth: Int, reqHeight: Int): Bitmap? {
        val options = BitmapFactory.Options()
        options.inJustDecodeBounds = true
        BitmapFactory.decodeFile(filePath, options)
        
        options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight)
        options.inJustDecodeBounds = false
        return try { BitmapFactory.decodeFile(filePath, options) } catch (e: Exception) { null }
    }

    private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        val (height: Int, width: Int) = options.run { outHeight to outWidth }
        var inSampleSize = 1
        if (height > reqHeight || width > reqWidth) {
            val halfHeight = height / 2
            val halfWidth = width / 2
            while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }

    inner class NoteAdapter(
        private var items: List<Note>,
        private val onClick: (Note) -> Unit,
        private val onLongClick: (Note) -> Unit
    ) : RecyclerView.Adapter<NoteAdapter.ViewHolder>() {

        inner class ViewHolder(
            val card: MaterialCardView, 
            val title: TextView, 
            val bodyText: TextView,
            val category: TextView,
            val hint: TextView,
            val tasksLayout: LinearLayout,
            val progressText: TextView,
            val reminderText: TextView,
            val noteImageContainer: FrameLayout
        ) : RecyclerView.ViewHolder(card)

        fun updateData(newItems: List<Note>) {
            items = newItems
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val card = MaterialCardView(parent.context).apply {
                layoutParams = RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT,
                    RecyclerView.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginEnd = 20
                    bottomMargin = 30
                }
                radius = 40f
                cardElevation = 20f
                strokeWidth = 1
                strokeColor = Color.parseColor("#44D4AF37")
                setCardBackgroundColor(Color.parseColor(COLOR_CARD))
                rippleColor = ColorStateList.valueOf(Color.parseColor("#33D4AF37"))
                isClickable = true
                isFocusable = true
                
                val innerLayout = LinearLayout(parent.context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(40, 40, 40, 40)
                }
                addView(innerLayout)
            }
            
            val innerLayout = card.getChildAt(0) as LinearLayout
            val title = TextView(parent.context).apply { textSize = 20f; typeface = Typeface.DEFAULT_BOLD }
            val noteImageContainer = FrameLayout(parent.context).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                visibility = View.GONE
                setPadding(0, 10, 0, 10)
            }
            val bodyText = TextView(parent.context).apply { textSize = 14f; maxLines = 3; setPadding(0, 10, 0, 10) }
            val category = TextView(parent.context).apply { textSize = 13f; setPadding(0, 5, 0, 5) }
            val hint = TextView(parent.context).apply { textSize = 11f; setPadding(0, 5, 0, 0) }
            val progressText = TextView(parent.context).apply { textSize = 12f; setTextColor(Color.parseColor(COLOR_GOLD)); setPadding(0, 5, 0, 5) }
            val reminderText = TextView(parent.context).apply { textSize = 12f; setTextColor(Color.parseColor("#4CAF50")); setPadding(0, 5, 0, 10) }
            val tasksLayout = LinearLayout(parent.context).apply { orientation = LinearLayout.VERTICAL }
            
            innerLayout.addView(title)
            innerLayout.addView(noteImageContainer)
            innerLayout.addView(bodyText)
            innerLayout.addView(category)
            innerLayout.addView(hint)
            innerLayout.addView(progressText)
            innerLayout.addView(reminderText)
            innerLayout.addView(tasksLayout)
            
            return ViewHolder(card, title, bodyText, category, hint, tasksLayout, progressText, reminderText, noteImageContainer)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val note = items[position]
            val textColor = Color.parseColor(COLOR_TEXT_PRIMARY) 

            if (note.colorHex.isNotBlank()) {
                holder.card.setCardBackgroundColor(Color.parseColor(note.colorHex))
            } else {
                holder.card.setCardBackgroundColor(Color.parseColor(COLOR_CARD))
            }

            holder.title.setTextColor(textColor)
            holder.title.text = if (note.isPrivate) "🔒 ${note.title}" else note.title

            holder.category.setTextColor(Color.parseColor(COLOR_TEXT_SECONDARY))
            holder.category.text = "دسته: ${note.category}"

            holder.noteImageContainer.removeAllViews()
            if (note.imagePaths.isNotEmpty() && !note.isPrivate) {
                holder.noteImageContainer.visibility = View.VISIBLE
                
                val firstImagePath = note.imagePaths[0]
                val bmp = loadBitmap(firstImagePath, 400, 400)
                if (bmp != null) {
                    val img = ImageView(holder.card.context).apply {
                        layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                        adjustViewBounds = true
                        scaleType = ImageView.ScaleType.FIT_CENTER
                        setImageBitmap(bmp)
                    }
                    holder.noteImageContainer.addView(img)

                    if (note.imagePaths.size > 1) {
                        val badge = TextView(holder.card.context).apply {
                            text = "+${note.imagePaths.size - 1}"
                            textSize = 12f
                            setTextColor(Color.WHITE)
                            setPadding(12, 6, 12, 6)
                            background = GradientDrawable().apply {
                                setColor(Color.parseColor("#CC000000"))
                                cornerRadius = 50f
                            }
                            layoutParams = FrameLayout.LayoutParams(
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT
                            ).apply {
                                gravity = Gravity.BOTTOM or Gravity.RIGHT
                                setMargins(10, 10, 10, 10)
                            }
                        }
                        holder.noteImageContainer.addView(badge)
                    }
                }
            } else {
                holder.noteImageContainer.visibility = View.GONE
            }

            if (note.isPrivate) {
                holder.hint.text = "برای مشاهده، رمز عبور را وارد کنید"
                holder.hint.setTextColor(Color.parseColor(COLOR_GOLD))
                holder.hint.visibility = View.VISIBLE
                holder.bodyText.visibility = View.GONE
                holder.progressText.visibility = View.GONE
                holder.reminderText.visibility = View.GONE
                holder.tasksLayout.visibility = View.GONE
            } else {
                holder.hint.visibility = View.GONE
                holder.bodyText.visibility = View.VISIBLE
                holder.bodyText.setTextColor(Color.parseColor(COLOR_TEXT_SECONDARY))
                holder.bodyText.text = note.body 
                
                if (note.reminderEnabled && note.reminderAt > 0) {
                    holder.reminderText.visibility = View.VISIBLE
                    val cal = Calendar.getInstance().apply { timeInMillis = note.reminderAt }
                    val jDate = gregorianToJalali(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH))
                    val timeStr = SimpleDateFormat("HH:mm", Locale.getDefault()).format(note.reminderAt)
                    holder.reminderText.text = "🔔 یادآور: ${jDate[2]}/${jDate[1]} - $timeStr"
                } else {
                    holder.reminderText.visibility = View.GONE
                }

                if (note.tasks.isNotEmpty()) {
                    holder.tasksLayout.visibility = View.VISIBLE
                    holder.progressText.visibility = View.VISIBLE
                    holder.tasksLayout.removeAllViews()

                    fun updateProgressUI() {
                        val done = note.tasks.count { it.done }
                        holder.progressText.text = "پیشرفت: $done از ${note.tasks.size}"
                    }
                    updateProgressUI()

                    note.tasks.forEach { task ->
                        val cb = CheckBox(holder.card.context).apply {
                            text = task.text
                            isChecked = task.done
                            setTextColor(textColor)
                            buttonTintList = ColorStateList.valueOf(Color.parseColor(COLOR_GOLD))
                            setOnCheckedChangeListener { _, checked ->
                                task.done = checked
                                updateProgressUI()
                                saveNotes()
                            }
                        }
                        holder.tasksLayout.addView(cb)
                    }
                } else {
                    holder.tasksLayout.visibility = View.GONE
                    holder.progressText.visibility = View.GONE
                }
            }

            holder.card.alpha = 0f
            holder.card.translationY = 50f
            holder.card.animate()
                .alpha(1f)
                .translationY(0f