package com.yadino.app

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.app.AlertDialog
import android.app.DatePickerDialog
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
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.media.AudioAttributes
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
import android.view.animation.DecelerateInterpolator
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
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

// رنگ‌های تم لاکچری
private const val COLOR_BG_TOP = "#1A1A1A"      // خاکستری خیلی تیره برای بالای صفحه
private const val COLOR_BG_BOTTOM = "#050505"   // مشکی مطلق برای پایین صفحه
private const val COLOR_CARD = "#181818"        // رنگ کارت‌ها
private const val COLOR_GOLD = "#D4AF37"        // طلایی
private const val COLOR_GOLD_DARK = "#AA8B2B"   // طلایی تیره
private const val COLOR_TEXT_PRIMARY = "#FFFFFF"
private const val COLOR_TEXT_SECONDARY = "#A0A0A0"

private const val REMINDER_NONE_TEXT = "یادآور تنظیم نشده است"

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

    private var editingNoteId: String? = null
    private var selectedReminderAt: Long = 0L
    private var selectedReminderEnabled = false
    private var selectedRepeatMode = "none"
    private var selectedRepeatCount = 0
    private var selectedRepeatInterval = 60
    private var selectedRingtoneUri = ""

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

    override fun onCreate(savedInstanceState: Bundle?) {
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

    private fun initializeSecureStorage() {
        val masterKey = MasterKey.Builder(this).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        securePrefs = EncryptedSharedPreferences.create(
            this, STORAGE_NAME, masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    // ===================================================================
    // دیزاین لاکچری + ظرافت‌های خاص
    // ===================================================================
    private fun buildScreen() {
        // 🔧 پس‌زمینه گرادیانت برای کل صفحه
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
            setPadding(40, 60, 40, 300) // فاصله بیشتر از پایین برای FAB
        }

        // 🔧 لوگوی طلایی با درخشش
        val titleTv = TextView(this).apply {
            text = "یادینو"
            textSize = 48f
            gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
            letterSpacing = 0.05f
            setPadding(0, 10, 0, 50)
            // سایه درخشش طلایی
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

        // 🔧 استایل فیلدها: حالت عادی تیره، حالت فوکوس قاب طلایی درخشان
        fun styleInput(editText: EditText) {
            editText.setTextColor(Color.parseColor(COLOR_TEXT_PRIMARY))
            editText.setHintTextColor(Color.parseColor(COLOR_TEXT_SECONDARY))
            
            val states = StateListDrawable()
            
            // حالت فوکوس (وقتی کاربر کلیک میکنه)
            val focusedBg = GradientDrawable().apply {
                setColor(Color.parseColor("#222222")) // کمی روشن تر
                cornerRadius = 35f
                setStroke(3, Color.parseColor(COLOR_GOLD))
            }
            // حالت عادی (وقتی کلیک نشده)
            val normalBg = GradientDrawable().apply {
                setColor(Color.parseColor(COLOR_CARD))
                cornerRadius = 35f
                setStroke(1, Color.parseColor("#333333")) // قاب خاکستری خیلی ظریف
            }
            
            states.addState(intArrayOf(android.R.attr.state_focused), focusedBg)
            states.addState(intArrayOf(), normalBg)
            
            editText.background = states
            editText.setPadding(35, 35, 35, 35)
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

        // دکمه‌های کپسولی طلایی (Pill shaped)
        fun createGoldButton(text: String, onClick: () -> Unit): Button {
            return Button(this).apply {
                this.text = text
                setTextColor(Color.BLACK)
                textSize = 16f
                typeface = Typeface.DEFAULT_BOLD
                
                val gradient = GradientDrawable(
                    GradientDrawable.Orientation.LEFT_RIGHT, 
                    intArrayOf(Color.parseColor(COLOR_GOLD), Color.parseColor(COLOR_GOLD_DARK))
                ).apply { cornerRadius = 100f } // گوشه‌های کاملاً گرد
                
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

        formContainer.addView(createGoldButton("🎤 شروع ضبط صدا") { toggleVoiceInput(false) }, matchWidth())
        formContainer.addView(createGoldButton("تنظیم یادآور") { configureReminder() }, matchWidth())

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
            setOnClickListener { formContainer.visibility = View.GONE; clearForm() }
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
        noteAdapter = NoteAdapter(notes, 
            onClick = { note ->
                if (note.isPrivate) {
                    requestPin("برای ویرایش، PIN را وارد کن") { if (it) { editNote(note); showForm() } }
                } else { editNote(note); showForm() }
            },
            onLongClick = { note -> showNoteOptionsMenu(note) },
            onTaskToggle = { saveNotes() }
        )
        notesRecyclerView.adapter = noteAdapter
        content.addView(notesRecyclerView, matchWidth())

        mainScroll.addView(content)
        root.addView(mainScroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // دکمه شناور (FAB) با سایه و درخشش
        val fab = FloatingActionButton(this).apply {
            setImageResource(android.R.drawable.ic_input_add)
            imageTintList = ColorStateList.valueOf(Color.parseColor(COLOR_BG_BOTTOM)) 
            backgroundTintList = ColorStateList.valueOf(Color.parseColor(COLOR_GOLD))
            compatElevation = 25f // سایه بیشتر
            
            setOnClickListener { view ->
                view.animate().rotation(90f).setDuration(200).withEndAction {
                    formContainer.visibility = View.VISIBLE
                    mainScroll.post { mainScroll.fullScroll(View.FOCUS_UP) }
                    view.animate().rotation(0f).setDuration(0).start()
                }.start()
            }
        }
        val fabParams = FrameLayout.LayoutParams(170, 170).apply { 
            gravity = Gravity.BOTTOM or Gravity.START
            setMargins(50, 50, 50, 50) 
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

    private fun showForm() { formContainer.visibility = View.VISIBLE; mainScroll.post { mainScroll.fullScroll(View.FOCUS_UP) } }

    private fun renderNotes() {
        val selectedCategory = if (::filterSpinner.isInitialized) filterSpinner.selectedItem?.toString() ?: "همه دسته‌ها" else "همه دسته‌ها"
        val visibleNotes = notes.filter { selectedCategory == "همه دسته‌ها" || it.category == selectedCategory }
        noteAdapter.updateData(visibleNotes)
    }

    // ===================================================================
    // آداپتر کارت‌ها
    // ===================================================================
    inner class NoteAdapter(
        private var items: List<Note>,
        private val onClick: (Note) -> Unit,
        private val onLongClick: (Note) -> Unit,
        private val onTaskToggle: () -> Unit
    ) : RecyclerView.Adapter<NoteAdapter.ViewHolder>() {

        inner class ViewHolder(val card: MaterialCardView, val title: TextView, val body: TextView, val progress: TextView, val tasksLayout: LinearLayout) : RecyclerView.ViewHolder(card)

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
                strokeColor = Color.parseColor("#44D4AF37") // حاشیه طلایی خیلی محو
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
            val body = TextView(parent.context).apply { textSize = 15f; maxLines = 4; setPadding(0, 15, 0, 15) }
            val progress = TextView(parent.context).apply { textSize = 13f }
            val tasksLayout = LinearLayout(parent.context).apply { orientation = LinearLayout.VERTICAL }
            
            innerLayout.addView(title); innerLayout.addView(body); innerLayout.addView(progress); innerLayout.addView(tasksLayout)
            return ViewHolder(card, title, body, progress, tasksLayout)
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
            holder.body.setTextColor(Color.parseColor(COLOR_TEXT_SECONDARY))
            holder.body.text = note.body
            
            holder.tasksLayout.removeAllViews()
            if (note.tasks.isNotEmpty()) {
                holder.progress.visibility = View.VISIBLE
                holder.progress.setTextColor(Color.parseColor(COLOR_GOLD))
                
                fun updateProgressUI() {
                    val done = note.tasks.count { it.done }
                    holder.progress.text = "پیشرفت: $done از ${note.tasks.size}"
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
                            onTaskToggle()
                        }
                    }
                    holder.tasksLayout.addView(cb)
                }
            } else {
                holder.progress.visibility = View.GONE
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

    // ===================================================================
    // منطق میکروفون و دیتابیس (همون قبلی)
    // ===================================================================
    private fun toggleVoiceInput(offline: Boolean) {
        if (continuousSpeech) stopContinuousSpeech()
        else {
            isOfflineMode = offline
            startVoiceInput()
        }
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
            override fun onReadyForSpeech(params: Bundle?) { runOnUiThread { Toast.makeText(this@MainActivity, "گوش می‌دهم...", Toast.LENGTH_SHORT).show() } }
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit

            override fun onPartialResults(partialResults: Bundle?) {
                val partial = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                if (partial.isNotBlank() && !isFinishing && !isDestroyed) {
                    runOnUiThread {
                        val t = speechTarget
                        if (t != null) {
                            val sep = if (t == taskInput && voiceBaseText.isNotBlank() && !voiceBaseText.endsWith("\n")) "\n" else " "
                            t.setText(if (voiceBaseText.isBlank()) partial else "$voiceBaseText$sep$partial")
                            t.setSelection(t.text.length)
                        }
                    }
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
                if (continuousSpeech) {
                    handler.removeCallbacks(restartRunnable)
                    handler.postDelayed(restartRunnable, 800)
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
                    handler.postDelayed(restartRunnable, 1000)
                }
            }
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        launchRecognizer()
    }

    private fun launchRecognizer() {
        if (isListening || isFinishing || isDestroyed) return
        isListening = true
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fa-IR")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1000L)
        }
        try { speechRecognizer?.startListening(intent) } catch (e: Exception) { isListening = false }
    }

    private fun stopContinuousSpeech() {
        continuousSpeech = false
        isListening = false
        handler.removeCallbacks(restartRunnable)
        try { speechRecognizer?.stopListening(); speechRecognizer?.destroy() } catch (_: Exception) {}
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

        saveNotes(); renderNotes(); clearForm(); formContainer.visibility = View.GONE
        Toast.makeText(this, "ذخیره شد", Toast.LENGTH_SHORT).show()
    }

    private fun clearForm() {
        titleInput.text.clear(); bodyInput.text.clear(); taskInput.text.clear()
        categorySpinner.setSelection(0); privateCheckBox.isChecked = false; editingNoteId = null
        selectedReminderAt = 0L; selectedReminderEnabled = false; selectedRepeatMode = "none"
        selectedRepeatCount = 0; selectedRepeatInterval = 60; selectedRingtoneUri = ""
        reminderSummary.text = REMINDER_NONE_TEXT
    }

    private fun editNote(note: Note) {
        editingNoteId = note.id; titleInput.setText(note.title); bodyInput.setText(note.body)
        privateCheckBox.isChecked = note.isPrivate
        val idx = categories.drop(1).indexOf(note.category); categorySpinner.setSelection(if (idx >= 0) idx else 0)
        taskInput.setText(note.tasks.joinToString("\n") { it.text })
        selectedReminderAt = note.reminderAt; selectedReminderEnabled = note.reminderEnabled
        selectedRepeatMode = note.repeatMode; selectedRepeatCount = note.repeatCount
        selectedRepeatInterval = note.repeatIntervalMinutes; selectedRingtoneUri = note.ringtoneUri
        reminderSummary.text = if (note.reminderEnabled && note.reminderAt > 0) "فعال: " + SimpleDateFormat("MM/dd HH:mm", Locale.getDefault()).format(note.reminderAt) else REMINDER_NONE_TEXT
    }

    private fun showNoteOptionsMenu(note: Note) {
        val options = arrayOf("🎨 تغییر رنگ", "🗑️ حذف")
        AlertDialog.Builder(this).setTitle("تنظیمات").setItems(options) { _, which ->
            if (which == 0) showColorPicker(note) else confirmDelete(note)
        }.show()
    }

    private fun showColorPicker(note: Note) {
        val colors = arrayOf("#181818", "#2B1010", "#1A2A15", "#15203B", "#2A1B38")
        val names = arrayOf("پیش‌فرض", "قرمز تیره", "سبز تیره", "آبی تیره", "بنفش تیره")
        AlertDialog.Builder(this).setTitle("رنگ").setItems(names) { _, w ->
            note.colorHex = colors[w]; saveNotes(); renderNotes()
        }.show()
    }

    private fun confirmDelete(note: Note) {
        val del = { AlertDialog.Builder(this).setTitle("حذف").setMessage("حذف شود؟").setNegativeButton("نه", null).setPositiveButton("بله") { _, _ ->
            cancelReminder(note.id); notes.removeAll { it.id == note.id }; saveNotes(); renderNotes()
        }.show() }
        if (note.isPrivate) requestPin("PIN") { if (it) del() } else del()
    }

    private fun configureReminder() {
        val cal = Calendar.getInstance().apply { if (selectedReminderAt > System.currentTimeMillis()) timeInMillis = selectedReminderAt else { add(Calendar.MINUTE, 5); set(Calendar.SECOND, 0) } }
        DatePickerDialog(this, { _, y, m, d ->
            cal.set(y, m, d)
            TimePickerDialog(this, { _, h, min ->
                cal.set(Calendar.HOUR_OF_DAY, h); cal.set(Calendar.MINUTE, min); cal.set(Calendar.SECOND, 0)
                selectedReminderAt = cal.timeInMillis; selectedReminderEnabled = true; configureRepeatOptions()
            }, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), true).show()
        }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
    }

    private fun configureRepeatOptions() {
        val choices = arrayOf("بدون تکرار", "روزانه", "هفتگی", "ماهانه", "دلخواه", "محدود")
        AlertDialog.Builder(this).setTitle("تکرار").setItems(choices) { _, w ->
            when (w) {
                0 -> { selectedRepeatMode = "none"; chooseRingtone() }
                1 -> { selectedRepeatMode = "daily"; chooseRingtone() }
                2 -> { selectedRepeatMode = "weekly"; chooseRingtone() }
                3 -> { selectedRepeatMode = "monthly"; chooseRingtone() }
                4 -> configureCustomInterval()
                5 -> configureRepeatCount()
            }
        }.show()
    }

    private fun configureCustomInterval() {
        val inp = EditText(this).apply { hint = "دقیقه"; inputType = InputType.TYPE_CLASS_NUMBER; setText(selectedRepeatInterval.toString()) }
        AlertDialog.Builder(this).setTitle("فاصله").setView(inp).setPositiveButton("OK") { _, _ ->
            selectedRepeatInterval = inp.text.toString().toIntOrNull() ?: 60; selectedRepeatMode = "interval"; chooseRingtone()
        }.show()
    }

    private fun configureRepeatCount() {
        val inp = EditText(this).apply { hint = "دفعات"; inputType = InputType.TYPE_CLASS_NUMBER; setText("3") }
        AlertDialog.Builder(this).setTitle("تعداد").setView(inp).setPositiveButton("OK") { _, _ ->
            selectedRepeatCount = (inp.text.toString().toIntOrNull() ?: 3).coerceIn(1, 100); selectedRepeatMode = "count"; chooseRingtone()
        }.show()
    }

    private fun chooseRingtone() {
        val opts = arrayOf("پیش‌فرض", "از گوشی", "بی‌صدا")
        AlertDialog.Builder(this).setTitle("صدا").setItems(opts) { _, w ->
            when (w) {
                0 -> { selectedRingtoneUri = ""; updateReminderSummary() }
                1 -> { ringtonePickerLauncher.launch(Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                    putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_NOTIFICATION or RingtoneManager.TYPE_ALARM)
                    if (selectedRingtoneUri.isNotBlank() && selectedRingtoneUri != "silent") putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, Uri.parse(selectedRingtoneUri))
                }) }
                2 -> { selectedRingtoneUri = "silent"; updateReminderSummary() }
            }
        }.show()
    }

    private fun updateReminderSummary() {
        reminderSummary.text = if (selectedReminderEnabled && selectedReminderAt > 0) {
            val d = SimpleDateFormat("MM/dd HH:mm", Locale.getDefault()).format(selectedReminderAt)
            val r = when (selectedRepeatMode) { "daily" -> "روزانه"; "weekly" -> "هفتگی"; "monthly" -> "ماهانه"; "interval" -> "هر $selectedRepeatInterval د"; "count" -> "$selectedRepeatCount بار"; else -> "بدون" }
            val s = when { selectedRingtoneUri.isBlank() -> "پیش‌فرض"; selectedRingtoneUri == "silent" -> "بی‌صدا"; else -> "دلخواه" }
            "یادآور: $d | $r | $s"
        } else REMINDER_NONE_TEXT
    }

    private fun scheduleReminder(note: Note) {
        if (!note.reminderEnabled || note.reminderAt <= 0) return
        val intent = Intent(this, ReminderReceiver::class.java).apply {
            putExtra("note_id", note.id); putExtra("note_title", note.title); putExtra("note_body", note.body); putExtra("repeat_mode", note.repeatMode); putExtra("repeat_count", note.repeatCount); putExtra("repeat_interval", note.repeatIntervalMinutes); putExtra("fired_count", note.reminderFiredCount); putExtra("ringtone_uri", note.ringtoneUri)
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
    }

    private fun showSetPinDialog(onSuccess: () -> Unit) {
        val inp = EditText(this).apply { hint = "PIN جدید"; inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD; setTextColor(Color.WHITE) }
        AlertDialog.Builder(this).setTitle("ساخت PIN").setView(inp).setNegativeButton("لغو", null).setPositiveButton("ذخیره") { _, _ ->
            if (inp.text.length < 4) Toast.makeText(this, "حداقل ۴ رقم", Toast.LENGTH_SHORT).show()
            else { securePrefs.edit().putString(PIN_KEY, MessageDigest.getInstance("SHA-256").digest(inp.text.toString().toByteArray()).joinToString("") { "%02x".format(it) }).apply(); onSuccess() }
        }.show()
    }

    private fun requestPin(message: String, callback: (Boolean) -> Unit) {
        val inp = EditText(this).apply { hint = "PIN"; inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD; setTextColor(Color.WHITE) }
        AlertDialog.Builder(this).setTitle(message).setView(inp).setNegativeButton("لغو") { _, _ -> callback(false) }.setPositiveButton("تأیید") { _, _ ->
            val hash = MessageDigest.getInstance("SHA-256").digest(inp.text.toString().toByteArray()).joinToString("") { "%02x".format(it) }
            val ok = securePrefs.getString(PIN_KEY, null) == hash; if (!ok) Toast.makeText(this, "اشتباه", Toast.LENGTH_SHORT).show(); callback(ok)
        }.show()
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val noteId = intent.getStringExtra("note_id") ?: return
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
        val builder = NotificationCompat.Builder(context, ch).setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle(title).setContentText(body).setStyle(NotificationCompat.BigTextStyle().bigText(body)).setPriority(NotificationCompat.PRIORITY_HIGH).setAutoCancel(true)
        val canNotify = Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        val pr = goAsync()
        if (canNotify) try { NotificationManagerCompat.from(context).notify(noteId.hashCode(), builder.build()) } catch (_: Exception) {}
        
        if (isCustom && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val r = RingtoneManager.getRingtone(context, Uri.parse(ringtone))
                if (r != null) { r.audioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION).build(); r.play(); Handler(Looper.getMainLooper()).postDelayed({ r.stop(); pr.finish() }, 5000) } else pr.finish()
            } catch (_: Exception) { pr.finish() }
        } else pr.finish()

        val next = when (repeatMode) {
            "daily" -> System.currentTimeMillis() + 86400000L
            "weekly" -> System.currentTimeMillis() + 604800000L
            "monthly" -> Calendar.getInstance().apply { add(Calendar.MONTH, 1) }.timeInMillis
            "interval" -> System.currentTimeMillis() + interval * 60000L
            "count" -> if (firedCount < repeatCount) System.currentTimeMillis() + interval * 60000L else 0L
            else -> 0L
        }
        if (next > System.currentTimeMillis()) {
            val ni = Intent(context, ReminderReceiver::class.java).apply { putExtra("note_id", noteId); putExtra("note_title", title); putExtra("note_body", body); putExtra("repeat_mode", repeatMode); putExtra("repeat_count", repeatCount); putExtra("repeat_interval", interval); putExtra("fired_count", firedCount + 1); putExtra("ringtone_uri", ringtone) }
            val pi = PendingIntent.getBroadcast(context, noteId.hashCode(), ni, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            try { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi) else am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi) } catch (_: Exception) {}
        }
    }
}