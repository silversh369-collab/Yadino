package com.yadino.app

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.app.Dialog
import android.app.PendingIntent
import android.app.TimePickerDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.media.AudioAttributes
import android.media.AudioManager
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
    private lateinit var audioManager: AudioManager

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
    
    private var pendingSnoozeNoteId: String? = null

    private var speechRecognizer: SpeechRecognizer? = null
    private var continuousSpeech = false
    private var speechTarget: EditText? = null
    private var isListening = false
    private var voiceBaseText = ""
    private var isOfflineMode = false
    private val handler = Handler(Looper.getMainLooper())
    private val restartRunnable = Runnable { if (continuousSpeech) launchRecognizer() }
    
    // تایمر برای تشخیص سکوت کاربر
    private val quietHandler = Handler(Looper.getMainLooper())
    private val quietRunnable = Runnable {
        runOnUiThread {
            if (continuousSpeech) {
                micStatusText.text = "صحبت کنید..."
                micStatusText.setTextColor(Color.parseColor(COLOR_TEXT_SECONDARY))
                startBlinkingStatus()
            }
        }
    }

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

    override fun onCreate(savedInstanceState: Bundle?) {
        if (intent?.action == "SHOW_SNOOZE_DIALOG") {
            pendingSnoozeNoteId = intent.getStringExtra("note_id")
        }

        var keepSplash = true
        val splashScreen = installSplashScreen()
        splashScreen.setKeepOnScreenCondition { keepSplash }
        Handler(Looper.getMainLooper()).postDelayed({ keepSplash = false }, 2000)

        super.onCreate(savedInstanceState)
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
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

        val titleTv = TextView(this).apply {
            text = "یادینو"
            textSize = 48f
            gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
            letterSpacing = 0.05f
            setPadding(0, 10, 0, 50)
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
        content.addView(titleTv)

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
            val reminderText: TextView
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
            val bodyText = TextView(parent.context).apply { textSize = 14f; maxLines = 3; setPadding(0, 10, 0, 10) }
            val category = TextView(parent.context).apply { textSize = 13f; setPadding(0, 5, 0, 5) }
            val hint = TextView(parent.context).apply { textSize = 11f; setPadding(0, 5, 0, 0) }
            val progressText = TextView(parent.context).apply { textSize = 12f; setTextColor(Color.parseColor(COLOR_GOLD)); setPadding(0, 5, 0, 5) }
            val reminderText = TextView(parent.context).apply { textSize = 12f; setTextColor(Color.parseColor("#4CAF50")); setPadding(0, 5, 0, 10) }
            val tasksLayout = LinearLayout(parent.context).apply { orientation = LinearLayout.VERTICAL }
            
            innerLayout.addView(title)
            innerLayout.addView(bodyText)
            innerLayout.addView(category)
            innerLayout.addView(hint)
            innerLayout.addView(progressText)
            innerLayout.addView(reminderText)
            innerLayout.addView(tasksLayout)
            
            return ViewHolder(card, title, bodyText, category, hint, tasksLayout, progressText, reminderText)
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
                .translationY(0f)
                .setDuration(300)
                .setInterpolator(DecelerateInterpolator())
                .start()

            holder.card.setOnClickListener { view ->
                view.animate().scaleX(0.95f).scaleY(0.95f).setDuration(100).withEndAction {
                    view.animate().scaleX(1f).scaleY(1f).setDuration(100).start()
                    onClick(note)
                }.start()
            }
            holder.card.setOnLongClickListener { onLongClick(note); true }
        }

        override fun getItemCount() = items.size
    }

    private fun showNoteDetails(note: Note) {
        val showDialog = {
            val dialog = Dialog(this)
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
            
            val content = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(50, 50, 50, 50)
                background = GradientDrawable().apply {
                    setColor(Color.parseColor(COLOR_CARD))
                    cornerRadius = 40f
                    setStroke(2, Color.parseColor(COLOR_GOLD))
                }
            }
            
            val scroll = ScrollView(this).apply { addView(content) }

            content.addView(TextView(this).apply { 
                text = note.title; textSize = 22f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD 
            })
            
            content.addView(TextView(this).apply { 
                text = "دسته: ${note.category}"; textSize = 14f; setTextColor(Color.parseColor(COLOR_GOLD)); setPadding(0, 10, 0, 20) 
            })
            
            content.addView(TextView(this).apply { 
                text = note.body; textSize = 16f; setTextColor(Color.WHITE); setLineSpacing(1.5f, 1.5f)
            })

            if (note.reminderEnabled && note.reminderAt > 0) {
                val cal = Calendar.getInstance().apply { timeInMillis = note.reminderAt }
                val jDate = gregorianToJalali(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH))
                val timeStr = SimpleDateFormat("HH:mm", Locale.getDefault()).format(note.reminderAt)
                content.addView(TextView(this).apply { 
                    text = "🔔 یادآور: ${jDate[2]}/${jDate[1]}/${jDate[0]} ساعت $timeStr"; textSize = 14f; setTextColor(Color.parseColor("#4CAF50")); setPadding(0, 20, 0, 10) 
                })
            }

            if (note.tasks.isNotEmpty()) {
                content.addView(TextView(this).apply { 
                    text = "کارها:"; textSize = 16f; setTextColor(Color.WHITE); setPadding(0, 20, 0, 10); typeface = Typeface.DEFAULT_BOLD
                })

                val progressTv = TextView(this).apply {
                    textSize = 14f
                    setTextColor(Color.parseColor(COLOR_GOLD))
                    setPadding(0, 5, 0, 10)
                }
                
                fun updateDialogProgress() {
                    val done = note.tasks.count { it.done }
                    progressTv.text = "پیشرفت: $done از ${note.tasks.size}"
                }
                updateDialogProgress()
                content.addView(progressTv)

                note.tasks.forEach { task ->
                    val cb = CheckBox(this).apply {
                        text = task.text
                        isChecked = task.done
                        setTextColor(Color.WHITE)
                        buttonTintList = ColorStateList.valueOf(Color.parseColor(COLOR_GOLD))
                        setOnCheckedChangeListener { _, checked ->
                            task.done = checked
                            updateDialogProgress()
                            saveNotes()
                        }
                    }
                    content.addView(cb)
                }
            }

            val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 40, 0, 0) }
            
            btnRow.addView(Button(this).apply {
                text = "ویرایش"
                setTextColor(Color.BLACK)
                background = GradientDrawable().apply { setColor(Color.parseColor(COLOR_GOLD)); cornerRadius = 100f }
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = 15 }
                setOnClickListener { 
                    dialog.dismiss()
                    editNote(note)
                    showForm() 
                }
            })
            
            btnRow.addView(Button(this).apply {
                text = "بستن"
                setTextColor(Color.WHITE)
                background = GradientDrawable().apply { setColor(Color.parseColor("#333333")); cornerRadius = 100f }
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener { dialog.dismiss() }
            })
            
            content.addView(btnRow)

            dialog.setContentView(scroll)
            dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
            dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            dialog.show()
        }

        if (note.isPrivate) {
            requestPin("برای مشاهده یادداشت خصوصی، PIN را وارد کن") { valid -> if (valid) showDialog() }
        } else {
            showDialog()
        }
    }

    private fun configureReminder() {
        val currentCal = Calendar.getInstance().apply {
            if (selectedReminderAt > System.currentTimeMillis()) timeInMillis = selectedReminderAt
            else add(Calendar.MINUTE, 5)
        }
        val gYear = currentCal.get(Calendar.YEAR)
        val gMonth = currentCal.get(Calendar.MONTH) + 1
        val gDay = currentCal.get(Calendar.DAY_OF_MONTH)
        
        val jDate = gregorianToJalali(gYear, gMonth, gDay)

        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 50, 50, 50)
            background = GradientDrawable().apply {
                setColor(Color.parseColor(COLOR_CARD))
                cornerRadius = 40f
                setStroke(2, Color.parseColor(COLOR_GOLD))
            }
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }

        layout.addView(TextView(this).apply {
            text = "انتخاب تاریخ (شمسی)"
            textSize = 20f
            setTextColor(Color.parseColor(COLOR_GOLD))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 30)
            typeface = Typeface.DEFAULT_BOLD
        })

        val spinnersRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            weightSum = 3f
        }

        val daySpinner = Spinner(this)
        val daysList = (1..31).map { it.toString() }
        daySpinner.adapter = spinnerAdapter(daysList)
        daySpinner.setSelection(jDate[2] - 1)
        spinnersRow.addView(daySpinner, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val monthSpinner = Spinner(this)
        monthSpinner.adapter = spinnerAdapter(persianMonths.toList())
        monthSpinner.setSelection(jDate[1] - 1)
        spinnersRow.addView(monthSpinner, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val yearSpinner = Spinner(this)
        val yearsList = (jDate[0] - 5..jDate[0] + 10).map { it.toString() }
        yearSpinner.adapter = spinnerAdapter(yearsList)
        yearSpinner.setSelection(5)
        spinnersRow.addView(yearSpinner, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        layout.addView(spinnersRow)

        val gregorianText = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.parseColor(COLOR_TEXT_SECONDARY))
            gravity = Gravity.CENTER
            setPadding(0, 20, 0, 30)
        }
        layout.addView(gregorianText)

        fun updateGregorianText() {
            val d = daySpinner.selectedItem.toString().toInt()
            val m = monthSpinner.selectedItemPosition + 1
            val y = yearSpinner.selectedItem.toString().toInt()
            val g = jalaliToGregorian(y, m, d)
            gregorianText.text = "معادل میلادی: ${g[0]}/${g[1].toString().padStart(2, '0')}/${g[2].toString().padStart(2, '0')}"
        }

        val listener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) { updateGregorianText() }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        daySpinner.onItemSelectedListener = listener
        monthSpinner.onItemSelectedListener = listener
        yearSpinner.onItemSelectedListener = listener
        updateGregorianText()

        layout.addView(Button(this).apply {
            text = "ادامه (انتخاب ساعت)"
            setTextColor(Color.BLACK)
            background = GradientDrawable().apply { setColor(Color.parseColor(COLOR_GOLD)); cornerRadius = 100f }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setOnClickListener {
                val selD = daySpinner.selectedItem.toString().toInt()
                val selM = monthSpinner.selectedItemPosition + 1
                val selY = yearSpinner.selectedItem.toString().toInt()
                val g = jalaliToGregorian(selY, selM, selD)
                
                dialog.dismiss()
                showTimePicker(g[0], g[1], g[2])
            }
        })

        dialog.setContentView(layout)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        dialog.show()
    }

    private fun showTimePicker(year: Int, month: Int, day: Int) {
        val cal = Calendar.getInstance()
        if (selectedReminderAt > System.currentTimeMillis()) cal.timeInMillis = selectedReminderAt
        
        TimePickerDialog(this, { _, h, min ->
            cal.set(year, month - 1, day, h, min, 0)
            cal.set(Calendar.MILLISECOND, 0)
            selectedReminderAt = cal.timeInMillis
            selectedReminderEnabled = true
            configureRepeatOptions()
        }, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), true).show()
    }

    private fun showSnoozeDialog(note: Note) {
        val options = arrayOf("۵ دقیقه دیگر", "۱۰ دقیقه دیگر", "۱۵ دقیقه دیگر", "۳۰ دقیقه دیگر", "۱ ساعت دیگر", "انتخاب زمان دلخواه...")
        val minutes = intArrayOf(5, 10, 15, 30, 60)

        showCustomListDialog("تعویق یادآور", options) { which ->
            if (which == 5) {
                val now = Calendar.getInstance()
                TimePickerDialog(this, { _, h, min ->
                    val snoozeTime = Calendar.getInstance().apply {
                        set(Calendar.HOUR_OF_DAY, h)
                        set(Calendar.MINUTE, min)
                        set(Calendar.SECOND, 0)
                    }
                    if (snoozeTime.timeInMillis <= System.currentTimeMillis()) {
                        snoozeTime.add(Calendar.DAY_OF_MONTH, 1)
                    }
                    applySnooze(note, snoozeTime.timeInMillis)
                }, now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE), true).show()
            } else {
                val delay = minutes[which]
                applySnooze(note, System.currentTimeMillis() + (delay * 60000L))
            }
        }
    }

    private fun applySnooze(note: Note, newTime: Long) {
        note.reminderAt = newTime
        note.reminderEnabled = true
        scheduleReminder(note)
        saveNotes()
        renderNotes()
        Toast.makeText(this, "یادآور مجدداً تنظیم شد.", Toast.LENGTH_SHORT).show()
    }

    private fun toggleVoiceInput(offline: Boolean) {
        if (continuousSpeech) {
            stopContinuousSpeech()
            micStatusText.visibility = View.GONE
            micButton.text = "🎤 شروع ضبط صدا"
        } else {
            isOfflineMode = offline
            startVoiceInput()
            micStatusText.visibility = View.VISIBLE
            micStatusText.text = "صحبت کنید..."
            micStatusText.setTextColor(Color.parseColor(COLOR_TEXT_SECONDARY))
            micButton.text = "🛑 توقف ضبط"
            
            startBlinkingStatus()
        }
    }

    private fun startBlinkingStatus() {
        blinkAnimation = AlphaAnimation(0.3f, 1.0f).apply {
            duration = 800
            repeatCount = Animation.INFINITE
            repeatMode = Animation.REVERSE
        }
        micStatusText.startAnimation(blinkAnimation)
    }

    private fun startVoiceInput() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), AUDIO_PERMISSION_CODE)
            return
        }
        val target = speechTarget ?: bodyInput
        voiceBaseText = target.text.toString()
        continuousSpeech = true
        
        speechRecognizer?.destroy()
        speechRecognizer = if (isOfflineMode && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && SpeechRecognizer.isOnDeviceRecognitionAvailable(this)) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
        } else {
            SpeechRecognizer.createSpeechRecognizer(this)
        }

        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            // 🔧 این توابع رو خالی گذاشتیم تا اندروید نتونه UI رو دستکاری کنه و متن رو عوض کنه
            override fun onReadyForSpeech(params: Bundle?) { }
            override fun onBeginningOfSpeech() { }
            override fun onEndOfSpeech() { }
            override fun onBufferReceived(buffer: ByteArray?) = Unit

            // 🔧 فقط با صدای واقعی کار می‌کنه
            override fun onRmsChanged(rmsdB: Float) {
                if (rmsdB > 3.5f) { // اگه صدای واقعی از حد مشخصی بالاتر بود
                    runOnUiThread {
                        if (micStatusText.text != "در حال گوش دادن...") {
                            micStatusText.text = "در حال گوش دادن..."
                            micStatusText.setTextColor(Color.parseColor("#4CAF50"))
                            micStatusText.clearAnimation()
                        }
                    }
                    // تایمر سکوت رو ریست کن
                    quietHandler.removeCallbacks(quietRunnable)
                    quietHandler.postDelayed(quietRunnable, 1500) // 1.5 ثانیه سکوت -> برگرد به صحبت کنید
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val partial = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                if (partial.isNotBlank() && !isFinishing && !isDestroyed) {
                    runOnUiThread {
                        if (micStatusText.text != "در حال گوش دادن...") {
                            micStatusText.text = "در حال گوش دادن..."
                            micStatusText.setTextColor(Color.parseColor("#4CAF50"))
                            micStatusText.clearAnimation()
                        }
                        val t = speechTarget
                        if (t != null) {
                            val sep = if (t == taskInput && voiceBaseText.isNotBlank() && !voiceBaseText.endsWith("\n")) "\n" else " "
                            t.setText(if (voiceBaseText.isBlank()) partial else "$voiceBaseText$sep$partial")
                            t.setSelection(t.text.length)
                        }
                    }
                    // تایمر سکوت رو ریست کن
                    quietHandler.removeCallbacks(quietRunnable)
                    quietHandler.postDelayed(quietRunnable, 1500)
                }
            }

            override fun onResults(results: Bundle?) {
                isListening = false
                val spoken = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                if (spoken.isNotBlank() && !isFinishing && !isDestroyed) {
                    runOnUiThread {
                        val t = speechTarget
                        if (t != null) {
                            val sep = if (t == taskInput && voiceBaseText.isNotBlank() && !voiceBaseText.endsWith("\n")) "\n" else " "
                            val finalText = if (voiceBaseText.isBlank()) spoken else "$voiceBaseText$sep$spoken"
                            t.setText(finalText)
                            t.setSelection(t.text.length)
                            voiceBaseText = finalText
                        }
                    }
                }
                
                // ری‌استارت با تاخیر کوتاه (بدون دستکاری UI)
                if (continuousSpeech) {
                    handler.removeCallbacks(restartRunnable)
                    handler.postDelayed(restartRunnable, 100)
                }
            }

            override fun onError(error: Int) {
                isListening = false
                if (error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED && isOfflineMode) {
                    isOfflineMode = false
                    speechRecognizer?.destroy()
                    speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this@MainActivity)
                    startVoiceInput()
                    return
                }
                
                if (continuousSpeech) {
                    handler.removeCallbacks(restartRunnable)
                    handler.postDelayed(restartRunnable, 100)
                }
            }
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        launchRecognizer()
    }

    private fun launchRecognizer() {
        if (isListening || isFinishing || isDestroyed) return
        isListening = true
        
        // 🔧 قطع کردن صدای سیستم برای جلوگیری از بوق
        try {
            audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0)
        } catch (e: Exception) {}

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fa-IR")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            
            // 🔧 تنظیمات طولانی برای اینکه خودش قطع نشه
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 3000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 10000L)
        }
        try { 
            speechRecognizer?.startListening(intent) 
        } catch (e: Exception) { 
            isListening = false 
            try {
                audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
            } catch (e: Exception) {}
        }
    }

    private fun stopContinuousSpeech() {
        continuousSpeech = false
        isListening = false
        handler.removeCallbacks(restartRunnable)
        quietHandler.removeCallbacks(quietRunnable)
        if (blinkAnimation != null) {
            micStatusText.clearAnimation()
            blinkAnimation = null
        }
        try { 
            speechRecognizer?.stopListening(); speechRecognizer?.destroy() 
            audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
        } catch (_: Exception) {}
        speechRecognizer = null
    }

    override fun onDestroy() { stopContinuousSpeech(); super.onDestroy() }

    private fun saveCurrentNote() {
        val title = titleInput.text.toString().trim(); val body = bodyInput.text.toString().trim()
        if (title.isBlank() && body.isBlank()) { Toast.makeText(this, "عنوان یا متن خالی است", Toast.LENGTH_SHORT).show(); return }
        val category = categorySpinner.selectedItem?.toString() ?: "عمومی"
        val isPrivate = privateCheckBox.isChecked

        if (isPrivate && securePrefs.getString(PIN_KEY, null).isNullOrBlank()) showSetPinDialog { persistCurrentNote(title, body, category, isPrivate) }
        else if (isPrivate) requestPin("برای ذخیره، PIN را وارد کن") { if (it) persistCurrentNote(title, body, category, true) }
        else persistCurrentNote(title, body, category, false)
    }

    private fun persistCurrentNote(title: String, body: String, category: String, isPrivate: Boolean) {
        val existing = editingNoteId?.let { id -> notes.find { it.id == id } }
        val note = existing ?: Note(title = title, body = body, category = category)
        note.title = title; note.body = body; note.category = category; note.isPrivate = isPrivate

        val oldDoneByText = note.tasks.associate { it.text to it.done }
        note.tasks.clear()
        taskInput.text.toString().split("\n").map { it.trim() }.filter { it.isNotEmpty() }.forEach { text ->
            note.tasks.add(Task(id = UUID.randomUUID().toString(), text = text, done = oldDoneByText[text] ?: false))
        }

        note.reminderAt = selectedReminderAt; note.reminderEnabled = selectedReminderEnabled
        note.repeatMode = selectedRepeatMode; note.repeatCount = selectedRepeatCount
        note.repeatIntervalMinutes = selectedRepeatInterval; note.ringtoneUri = selectedRingtoneUri

        if (existing == null) notes.add(0, note)
        if (note.reminderEnabled && note.reminderAt > System.currentTimeMillis()) scheduleReminder(note) else cancelReminder(note.id)

        saveNotes(); renderNotes(); clearForm(); 
        formContainer.visibility = View.GONE
        fab.show()
        Toast.makeText(this, "ذخیره شد", Toast.LENGTH_SHORT).show()
    }

    private fun clearForm() {
        titleInput.text.clear(); bodyInput.text.clear(); taskInput.text.clear()
        categorySpinner.setSelection(0); privateCheckBox.isChecked = false; editingNoteId = null
        selectedReminderAt = 0L; selectedReminderEnabled = false; selectedRepeatMode = "none"
        selectedRepeatCount = 0; selectedRepeatInterval = 60; selectedRingtoneUri = ""
        reminderSummary.text = REMINDER_NONE_TEXT
        micStatusText.visibility = View.GONE
        micButton.text = "🎤 شروع ضبط صدا"
    }

    private fun editNote(note: Note) {
        editingNoteId = note.id; titleInput.setText(note.title); bodyInput.setText(note.body)
        privateCheckBox.isChecked = note.isPrivate
        val idx = categories.drop(1).indexOf(note.category); categorySpinner.setSelection(if (idx >= 0) idx else 0)
        taskInput.setText(note.tasks.joinToString("\n") { it.text })
        selectedReminderAt = note.reminderAt; selectedReminderEnabled = note.reminderEnabled
        selectedRepeatMode = note.repeatMode; selectedRepeatCount = note.repeatCount
        selectedRepeatInterval = note.repeatIntervalMinutes; selectedRingtoneUri = note.ringtoneUri
        
        if (note.reminderEnabled && note.reminderAt > 0) {
            val cal = Calendar.getInstance().apply { timeInMillis = note.reminderAt }
            val jDate = gregorianToJalali(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH))
            reminderSummary.text = "فعال: ${jDate[2]}/${jDate[1]}/${jDate[0]} ساعت ${SimpleDateFormat("HH:mm", Locale.getDefault()).format(note.reminderAt)}"
        } else {
            reminderSummary.text = REMINDER_NONE_TEXT
        }
    }

    private fun showNoteOptionsMenu(note: Note) {
        val options = arrayOf("🎨 تغییر رنگ", "🗑️ حذف")
        showCustomListDialog("تنظیمات", options) { which ->
            if (which == 0) {
                if (note.isPrivate) {
                    requestPin("برای تغییر رنگ یادداشت خصوصی، PIN را وارد کن") { valid -> if (valid) showColorPicker(note) }
                } else showColorPicker(note)
            } else {
                confirmDelete(note)
            }
        }
    }

    private fun showColorPicker(note: Note) {
        val colors = arrayOf("#181818", "#2B1010", "#1A2A15", "#15203B", "#2A1B38")
        val names = arrayOf("پیش‌فرض", "قرمز تیره", "سبز تیره", "آبی تیره", "بنفش تیره")
        showCustomListDialog("انتخاب رنگ", names) { w ->
            note.colorHex = colors[w]; saveNotes(); renderNotes()
        }
    }

    private fun confirmDelete(note: Note) {
        val del = { showCustomConfirmDialog("حذف", "آیا از حذف این یادداشت مطمئن هستید؟") {
            cancelReminder(note.id); notes.removeAll { it.id == note.id }; saveNotes(); renderNotes()
        } }
        if (note.isPrivate) requestPin("برای حذف یادداشت خصوصی، PIN را وارد کن") { if (it) del() } else del()
    }

    private fun configureRepeatOptions() {
        val choices = arrayOf("بدون تکرار", "روزانه", "هفتگی", "ماهانه", "دلخواه", "محدود")
        showCustomListDialog("تکرار", choices) { w ->
            when (w) {
                0 -> { selectedRepeatMode = "none"; chooseRingtone() }
                1 -> { selectedRepeatMode = "daily"; chooseRingtone() }
                2 -> { selectedRepeatMode = "weekly"; chooseRingtone() }
                3 -> { selectedRepeatMode = "monthly"; chooseRingtone() }
                4 -> configureCustomInterval()
                5 -> configureRepeatCount()
            }
        }
    }

    private fun configureCustomInterval() {
        showCustomInputDialog("فاصله", "دقیقه", selectedRepeatInterval.toString()) { text ->
            selectedRepeatInterval = text.toIntOrNull() ?: 60; selectedRepeatMode = "interval"; chooseRingtone()
        }
    }

    private fun configureRepeatCount() {
        showCustomInputDialog("تعداد", "دفعات", "3") { text ->
            selectedRepeatCount = (text.toIntOrNull() ?: 3).coerceIn(1, 100); selectedRepeatMode = "count"; chooseRingtone()
        }
    }

    private fun chooseRingtone() {
        val opts = arrayOf("پیش‌فرض", "از گوشی", "بی‌صدا")
        showCustomListDialog("صدا", opts) { w ->
            when (w) {
                0 -> { selectedRingtoneUri = ""; updateReminderSummary() }
                1 -> { ringtonePickerLauncher.launch(Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                    putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_NOTIFICATION or RingtoneManager.TYPE_ALARM)
                    if (selectedRingtoneUri.isNotBlank() && selectedRingtoneUri != "silent") putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, Uri.parse(selectedRingtoneUri))
                }) }
                2 -> { selectedRingtoneUri = "silent"; updateReminderSummary() }
            }
        }
    }

    private fun updateReminderSummary() {
        if (selectedReminderEnabled && selectedReminderAt > 0) {
            val cal = Calendar.getInstance().apply { timeInMillis = selectedReminderAt }
            val jDate = gregorianToJalali(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH))
            val timeStr = SimpleDateFormat("HH:mm", Locale.getDefault()).format(selectedReminderAt)
            
            val r = when (selectedRepeatMode) { "daily" -> "روزانه"; "weekly" -> "هفتگی"; "monthly" -> "ماهانه"; "interval" -> "هر $selectedRepeatInterval د"; "count" -> "$selectedRepeatCount بار"; else -> "بدون" }
            val s = when { selectedRingtoneUri.isBlank() -> "پیش‌فرض"; selectedRingtoneUri == "silent" -> "بی‌صدا"; else -> "دلخواه" }
            reminderSummary.text = "یادآور: ${jDate[2]}/${jDate[1]}/${jDate[0]} - $timeStr | $r | $s"
        } else {
            reminderSummary.text = REMINDER_NONE_TEXT
        }
    }

    private fun scheduleReminder(note: Note) {
        if (!note.reminderEnabled || note.reminderAt <= 0) return
        
        val displayTitle = if (note.isPrivate) "🔒 ${note.title}" else note.title
        val displayBody = if (note.isPrivate) "متن یادداشت قفل است. برای مشاهده برنامه را باز کنید." else note.body

        val intent = Intent(this, ReminderReceiver::class.java).apply {
            putExtra("note_id", note.id)
            putExtra("note_title", displayTitle)
            putExtra("note_body", displayBody)
            putExtra("repeat_mode", note.repeatMode)
            putExtra("repeat_count", note.repeatCount)
            putExtra("repeat_interval", note.repeatIntervalMinutes)
            putExtra("fired_count", note.reminderFiredCount)
            putExtra("ringtone_uri", note.ringtoneUri)
        }
        val pi = PendingIntent.getBroadcast(this, note.id.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        try { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, note.reminderAt, pi) else am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, note.reminderAt, pi) } catch (_: Exception) {}
    }

    private fun cancelReminder(noteId: String) {
        val pi = PendingIntent.getBroadcast(this, noteId.hashCode(), Intent(this, ReminderReceiver::class.java), PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
        if (pi != null) { (getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(pi); pi.cancel() }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val m = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            m.createNotificationChannel(android.app.NotificationChannel(CHANNEL_ID, "یادآور", android.app.NotificationManager.IMPORTANCE_HIGH))
            m.createNotificationChannel(android.app.NotificationChannel(CHANNEL_ID_SILENT, "بی‌صدا", android.app.NotificationManager.IMPORTANCE_HIGH).apply { setSound(null, null) })
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIFICATION_PERMISSION_CODE)
    }

    private fun saveNotes() {
        try {
            val a = JSONArray()
            notes.forEach { n ->
                a.put(JSONObject().apply {
                    put("id", n.id); put("title", n.title); put("body", n.body); put("category", n.category); put("private", n.isPrivate); put("colorHex", n.colorHex)
                    val ta = JSONArray(); n.tasks.forEach { ta.put(JSONObject().apply { put("id", it.id); put("text", it.text); put("done", it.done) }) }; put("tasks", ta)
                    put("reminderAt", n.reminderAt); put("reminderEnabled", n.reminderEnabled); put("repeatMode", n.repeatMode); put("repeatCount", n.repeatCount); put("repeatIntervalMinutes", n.repeatIntervalMinutes); put("ringtoneUri", n.ringtoneUri); put("reminderFiredCount", n.reminderFiredCount)
                })
            }
            securePrefs.edit().putString(NOTES_KEY, a.toString()).apply()
        } catch (e: Exception) { Log.e(TAG, "Save fail", e) }
    }

    private fun loadNotes() {
        notes.clear()
        val raw = securePrefs.getString(NOTES_KEY, null) ?: return
        try {
            val a = JSONArray(raw)
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i) ?: continue
                val t = mutableListOf<Task>(); val ta = o.optJSONArray("tasks") ?: JSONArray()
                for (j in 0 until ta.length()) { val to = ta.optJSONObject(j) ?: continue; t.add(Task(id = to.optString("id").ifBlank { UUID.randomUUID().toString() }, text = to.optString("text"), done = to.optBoolean("done", false))) }
                notes.add(Note(id = o.optString("id").ifBlank { UUID.randomUUID().toString() }, title = o.optString("title"), body = o.optString("body"), category = o.optString("category", "عمومی"), isPrivate = o.optBoolean("private", false), colorHex = o.optString("colorHex", ""), tasks = t, reminderAt = o.optLong("reminderAt", 0L), reminderEnabled = o.optBoolean("reminderEnabled", false), repeatMode = o.optString("repeatMode", "none"), repeatCount = o.optInt("repeatCount", 0), repeatIntervalMinutes = o.optInt("repeatIntervalMinutes", 60), ringtoneUri = o.optString("ringtoneUri", ""), reminderFiredCount = o.optInt("reminderFiredCount", 0)))
            }
        } catch (e: Exception) { Log.e(TAG, "Load fail", e) }
        
        pendingSnoozeNoteId?.let { id ->
            val note = notes.find { it.id == id }
            if (note != null) {
                showSnoozeDialog(note)
            }
            pendingSnoozeNoteId = null
        }
    }

    private fun showSetPinDialog(onSuccess: () -> Unit) {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 50, 50, 50)
            background = GradientDrawable().apply {
                setColor(Color.parseColor(COLOR_CARD))
                cornerRadius = 40f
                setStroke(2, Color.parseColor(COLOR_GOLD))
            }
        }

        content.addView(TextView(this).apply { 
            text = "ساخت PIN جدید"; textSize = 18f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD; setPadding(0, 0, 0, 20)
        })

        val inp = EditText(this).apply { 
            hint = "PIN (حداقل ۴ رقم)"
            setHintTextColor(Color.parseColor(COLOR_TEXT_SECONDARY))
            setTextColor(Color.WHITE)
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            background = GradientDrawable().apply { setColor(Color.parseColor("#202020")); cornerRadius = 20f; setStroke(1, Color.parseColor(COLOR_TEXT_SECONDARY)) }
            setPadding(30, 30, 30, 30)
        }
        content.addView(inp)

        val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 30, 0, 0) }
        
        btnRow.addView(Button(this).apply {
            text = "لغو"
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply { setColor(Color.parseColor("#333333")); cornerRadius = 100f }
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = 15 }
            setOnClickListener { dialog.dismiss() }
        })
        
        btnRow.addView(Button(this).apply {
            text = "ذخیره"
            setTextColor(Color.BLACK)
            background = GradientDrawable().apply { setColor(Color.parseColor(COLOR_GOLD)); cornerRadius = 100f }
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                val pin = inp.text.toString()
                if (pin.length < 4) {
                    Toast.makeText(this@MainActivity, "حداقل ۴ رقم وارد کنید.", Toast.LENGTH_SHORT).show()
                } else {
                    securePrefs.edit().putString(PIN_KEY, MessageDigest.getInstance("SHA-256").digest(pin.toByteArray()).joinToString("") { "%02x".format(it) }).apply()
                    dialog.dismiss()
                    onSuccess()
                }
            }
        })
        content.addView(btnRow)

        dialog.setContentView(content)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        dialog.show()
    }

    private fun requestPin(message: String, callback: (Boolean) -> Unit) {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 50, 50, 50)
            background = GradientDrawable().apply {
                setColor(Color.parseColor(COLOR_CARD))
                cornerRadius = 40f
                setStroke(2, Color.parseColor(COLOR_GOLD))
            }
        }

        content.addView(TextView(this).apply { 
            text = message; textSize = 16f; setTextColor(Color.WHITE); setPadding(0, 0, 0, 20)
        })

        val inp = EditText(this).apply { 
            hint = "PIN"
            setHintTextColor(Color.parseColor(COLOR_TEXT_SECONDARY))
            setTextColor(Color.WHITE)
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            background = GradientDrawable().apply { setColor(Color.parseColor("#202020")); cornerRadius = 20f; setStroke(1, Color.parseColor(COLOR_TEXT_SECONDARY)) }
            setPadding(30, 30, 30, 30)
        }
        content.addView(inp)

        val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 30, 0, 0) }
        
        btnRow.addView(Button(this).apply {
            text = "لغو"
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply { setColor(Color.parseColor("#333333")); cornerRadius = 100f }
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = 15 }
            setOnClickListener { 
                dialog.dismiss()
                callback(false) 
            }
        })
        
        btnRow.addView(Button(this).apply {
            text = "تأیید"
            setTextColor(Color.BLACK)
            background = GradientDrawable().apply { setColor(Color.parseColor(COLOR_GOLD)); cornerRadius = 100f }
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                val hash = MessageDigest.getInstance("SHA-256").digest(inp.text.toString().toByteArray()).joinToString("") { "%02x".format(it) }
                val ok = securePrefs.getString(PIN_KEY, null) == hash
                if (!ok) Toast.makeText(this@MainActivity, "PIN اشتباه است.", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
                callback(ok)
            }
        })
        content.addView(btnRow)

        dialog.setContentView(content)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        dialog.show()
    }

    private fun showCustomListDialog(title: String, items: Array<String>, onItemSelected: (Int) -> Unit) {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 50, 50, 50)
            background = GradientDrawable().apply {
                setColor(Color.parseColor(COLOR_CARD))
                cornerRadius = 40f
                setStroke(2, Color.parseColor(COLOR_GOLD))
            }
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }

        layout.addView(TextView(this).apply {
            text = title
            textSize = 20f
            setTextColor(Color.parseColor(COLOR_GOLD))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 30)
            typeface = Typeface.DEFAULT_BOLD
        })

        val listLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        items.forEachIndexed { index, item ->
            val btn = TextView(this).apply {
                text = item
                textSize = 16f
                setTextColor(Color.WHITE)
                setPadding(30, 40, 30, 40)
                gravity = Gravity.CENTER
                background = RippleDrawable(ColorStateList.valueOf(Color.parseColor("#33D4AF37")), null, null)
                setOnClickListener {
                    dialog.dismiss()
                    onItemSelected(index)
                }
            }
            listLayout.addView(btn)
            if (index < items.size - 1) {
                listLayout.addView(View(this).apply {
                    setBackgroundColor(Color.parseColor("#333333"))
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
                })
            }
        }

        layout.addView(listLayout)
        dialog.setContentView(layout)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        dialog.show()
    }

    private fun showCustomInputDialog(title: String, hint: String, initialText: String, onConfirm: (String) -> Unit) {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 50, 50, 50)
            background = GradientDrawable().apply {
                setColor(Color.parseColor(COLOR_CARD))
                cornerRadius = 40f
                setStroke(2, Color.parseColor(COLOR_GOLD))
            }
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }

        layout.addView(TextView(this).apply {
            text = title
            textSize = 18f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, 20)
        })

        val inp = EditText(this).apply {
            this.hint = hint
            setHintTextColor(Color.parseColor(COLOR_TEXT_SECONDARY))
            setTextColor(Color.WHITE)
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(initialText)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#202020"))
                cornerRadius = 20f
                setStroke(1, Color.parseColor(COLOR_TEXT_SECONDARY))
            }
            setPadding(30, 30, 30, 30)
        }
        layout.addView(inp)

        val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 30, 0, 0) }
        btnRow.addView(Button(this).apply {
            text = "لغو"
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply { setColor(Color.parseColor("#333333")); cornerRadius = 100f }
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = 15 }
            setOnClickListener { dialog.dismiss() }
        })
        btnRow.addView(Button(this).apply {
            text = "تأیید"
            setTextColor(Color.BLACK)
            background = GradientDrawable().apply { setColor(Color.parseColor(COLOR_GOLD)); cornerRadius = 100f }
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                dialog.dismiss()
                onConfirm(inp.text.toString())
            }
        })
        layout.addView(btnRow)

        dialog.setContentView(layout)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        dialog.show()
    }

    private fun showCustomConfirmDialog(title: String, message: String, onConfirm: () -> Unit) {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 50, 50, 50)
            background = GradientDrawable().apply {
                setColor(Color.parseColor(COLOR_CARD))
                cornerRadius = 40f
                setStroke(2, Color.parseColor(COLOR_GOLD))
            }
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }

        layout.addView(TextView(this).apply {
            text = title
            textSize = 20f
            setTextColor(Color.parseColor(COLOR_GOLD))
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, 20)
        })

        layout.addView(TextView(this).apply {
            text = message
            textSize = 16f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 30)
        })

        val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        btnRow.addView(Button(this).apply {
            text = "نه"
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply { setColor(Color.parseColor("#333333")); cornerRadius = 100f }
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = 15 }
            setOnClickListener { dialog.dismiss() }
        })
        btnRow.addView(Button(this).apply {
            text = "بله"
            setTextColor(Color.BLACK)
            background = GradientDrawable().apply { setColor(Color.parseColor(COLOR_GOLD)); cornerRadius = 100f }
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                dialog.dismiss()
                onConfirm()
            }
        })
        layout.addView(btnRow)

        dialog.setContentView(layout)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        dialog.show()
    }

    private fun jalaliToGregorian(jY: Int, jM: Int, jD: Int): IntArray {
        var jy = jY - 979
        var jm = jM - 1
        var jd = jD - 1
        var jDayNo = 365 * jy + (jy / 33) * 8 + ((jy % 33) + 3) / 4
        for (i in 0 until jm) {
            if (i < 6) jDayNo += 31 else jDayNo += 30
        }
        jDayNo += jd
        var gDayNo = jDayNo + 79
        var gy = 1600 + 400 * (gDayNo / 146097)
        gDayNo %= 146097
        var leap = true
        if (gDayNo >= 36525) {
            gDayNo--
            gy += 100 * (gDayNo / 36524)
            gDayNo %= 36524
            if (gDayNo >= 365) gDayNo++ else leap = false
        }
        gy += 4 * (gDayNo / 1461)
        gDayNo %= 1461
        if (gDayNo >= 366) {
            leap = false
            gDayNo--
            gy += gDayNo / 365
            gDayNo %= 365
        }
        var i = 0
        var leapDays = intArrayOf(31, if (leap) 29 else 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)
        while (gDayNo >= leapDays[i]) {
            gDayNo -= leapDays[i]
            i++
        }
        return intArrayOf(gy, i + 1, gDayNo + 1)
    }

    private fun gregorianToJalali(gY: Int, gM: Int, gD: Int): IntArray {
        val gDaysInMonth = intArrayOf(31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)
        var gy = gY - 1600
        var gm = gM - 1
        var gd = gD - 1
        var gDayNo = 365 * gy + (gy + 3) / 4 - (gy + 99) / 100 + (gy + 399) / 400
        for (i in 0 until gm) gDayNo += gDaysInMonth[i]
        if (gm > 1 && ((gy % 4 == 0 && gy % 100 != 0) || (gy % 400 == 0))) gDayNo++
        gDayNo += gd
        var jDayNo = gDayNo - 79
        val jNp = jDayNo / 12053
        jDayNo %= 12053
        var jy = 979 + 33 * jNp + 4 * (jDayNo / 1461)
        jDayNo %= 1461
        if (jDayNo >= 366) {
            jy += (jDayNo - 1) / 365
            jDayNo = (jDayNo - 1) % 365
        }
        var jm = 0
        var i = 0
        while (i < 11 && jDayNo >= if (i < 6) 31 else 30) {
            jDayNo -= if (i < 6) 31 else 30
            i++
        }
        jm = i + 1
        val jd = jDayNo + 1
        return intArrayOf(jy, jm, jd)
    }
}

class ReminderReceiver : BroadcastReceiver() {
    companion object {
        var mediaPlayer: MediaPlayer? = null
    }

    override fun onReceive(context: Context, intent: Intent) {
        val noteId = intent.getStringExtra("note_id") ?: return

        if (intent.action == "STOP_ALARM" || intent.action == "SNOOZE_ALARM") {
            try {
                mediaPlayer?.let { if (it.isPlaying) it.stop(); it.release() }
                mediaPlayer = null
                NotificationManagerCompat.from(context).cancel(noteId.hashCode())
            } catch (e: Exception) {}
            
            if (intent.action == "SNOOZE_ALARM") {
                val openIntent = Intent(context, MainActivity::class.java).apply {
                    action = "SHOW_SNOOZE_DIALOG"
                    putExtra("note_id", noteId)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                context.startActivity(openIntent)
            }
            return
        }

        val title = intent.getStringExtra("note_title") ?: "یادآور"
        val body = intent.getStringExtra("note_body").orEmpty()
        val repeatMode = intent.getStringExtra("repeat_mode") ?: "none"
        val repeatCount = intent.getIntExtra("repeat_count", 0)
        val interval = intent.getIntExtra("repeat_interval", 60)
        val firedCount = intent.getIntExtra("fired_count", 0)
        val ringtone = intent.getStringExtra("ringtone_uri").orEmpty()

        val isCustom = ringtone.isNotBlank() && ringtone != "silent"
        val isSilent = ringtone == "silent"
        val ch = if (isCustom || isSilent) CHANNEL_ID_SILENT else CHANNEL_ID

        val stopIntent = Intent(context, ReminderReceiver::class.java).apply {
            action = "STOP_ALARM"
            putExtra("note_id", noteId)
        }
        val stopPi = PendingIntent.getBroadcast(context, noteId.hashCode() + 1, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val snoozeIntent = Intent(context, ReminderReceiver::class.java).apply {
            action = "SNOOZE_ALARM"
            putExtra("note_id", noteId)
        }
        val snoozePi = PendingIntent.getBroadcast(context, noteId.hashCode() + 2, snoozeIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val builder = NotificationCompat.Builder(context, ch)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .addAction(android.R.drawable.ic_menu_recent_history, "تعویق", snoozePi)
            .addAction(android.R.drawable.ic_delete, "توقف صدا", stopPi)
            .setOngoing(true)
            .setAutoCancel(false)

        val canNotify = Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        val pr = goAsync()
        
        if (canNotify) {
            try { NotificationManagerCompat.from(context).notify(noteId.hashCode(), builder.build()) } catch (_: Exception) {}
        }
        
        if (!isSilent) {
            try {
                mediaPlayer?.release()
                val uriToPlay = if (isCustom) Uri.parse(ringtone) else RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)

                mediaPlayer = MediaPlayer().apply {
                    setDataSource(context, uriToPlay)
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .build()
                    )
                    isLooping = true
                    prepare()
                    start()
                }
            } catch (e: Exception) {
                Log.e(TAG, "MediaPlayer error: ${e.message}")
            }
        }
        
        pr.finish()

        val next = when (repeatMode) {
            "daily" -> System.currentTimeMillis() + 86400000L
            "weekly" -> System.currentTimeMillis() + 604800000L
            "monthly" -> Calendar.getInstance().apply { add(Calendar.MONTH, 1) }.timeInMillis
            "interval" -> System.currentTimeMillis() + interval * 60000L
            "count" -> if (firedCount < repeatCount) System.currentTimeMillis() + interval * 60000L else 0L
            else -> 0L
        }
        if (next > System.currentTimeMillis()) {
            val ni = Intent(context, ReminderReceiver::class.java).apply { 
                putExtra("note_id", noteId); putExtra("note_title", title); putExtra("note_body", body); 
                putExtra("repeat_mode", repeatMode); putExtra("repeat_count", repeatCount); 
                putExtra("repeat_interval", interval); putExtra("fired_count", firedCount + 1); 
                putExtra("ringtone_uri", ringtone) 
            }
            val pi = PendingIntent.getBroadcast(context, noteId.hashCode(), ni, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            try { 
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi) 
                else am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi) 
            } catch (_: Exception) {}
        }
    }
}