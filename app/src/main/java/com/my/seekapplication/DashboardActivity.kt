package com.my.seekapplication

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.RelativeLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.my.seekapplication.crypto.CredentialPolicy
import com.my.seekapplication.setup.SetupWizardActivity

/**
 * DashboardActivity: Vault gate screen with custom Swipe-to-Unlock slider.
 *
 * Implements:
 * 1. Hardware monotonic exponential backoff lockout integration.
 * 2. Unlocks Real or Decoy vault via symmetric evaluation.
 * 3. Offline Recovery Code unwrap dialog (eliminates insecure question bypass).
 * 4. Automatic redirection to SetupWizardActivity if setup is incomplete.
 */
class DashboardActivity : AppCompatActivity() {

    private lateinit var swipeThumb: RelativeLayout
    private lateinit var swipeTrack: RelativeLayout
    private var dX = 0f

    private var isUnlockTriggered = false
    private var activeDialog: Dialog? = null

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // FLAG_SECURE prevents screenshots and screen recording
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        supportActionBar?.hide()
        enableEdgeToEdge()

        setContentView(R.layout.activity_dashboard)

        val root = findViewById<RelativeLayout>(R.id.rootDashboard)

        // Android 16 Window Insets: avoid edge overlap
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val density = resources.displayMetrics.density
            val sidePad = (16 * density).toInt()
            root.setPadding(
                systemBars.left + sidePad,
                systemBars.top + (12 * density).toInt(),
                systemBars.right + sidePad,
                systemBars.bottom + (12 * density).toInt()
            )
            insets
        }

        swipeThumb = findViewById(R.id.swipeThumb)
        swipeTrack = findViewById(R.id.swipeTrack)

        // SWIPE TO UNLOCK LOGIC with single-trigger guard
        swipeThumb.setOnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    dX = view.x - event.rawX
                    isUnlockTriggered = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    var newX = event.rawX + dX
                    val maxX = swipeTrack.width.toFloat() - view.width.toFloat()

                    if (newX < 0) newX = 0f
                    if (newX > maxX) newX = maxX

                    view.x = newX

                    if (!isUnlockTriggered && newX >= maxX - 12f) {
                        isUnlockTriggered = true
                        view.animate().x(0f).setDuration(200).start()
                        onSwipeThresholdReached()
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    view.animate().x(0f).setDuration(200).start()
                    isUnlockTriggered = false
                    true
                }
                else -> false
            }
        }

        // Tap on Header or Lock icon allows direct recovery if user forgot password
        findViewById<View>(R.id.headerText)?.setOnClickListener {
            showPasswordRecoveryDialog()
        }
        findViewById<View>(R.id.secureLockImage)?.setOnClickListener {
            showPasswordRecoveryDialog()
        }

        // Handle direct recovery intent from VoiceRecorderActivity
        if (intent.getBooleanExtra("START_RECOVERY", false)) {
            swipeTrack.post {
                showPasswordRecoveryDialog()
            }
        }
    }

    private fun onSwipeThresholdReached() {
        if (isFinishing || isDestroyed) return
        if (activeDialog?.isShowing == true) return

        if (!VaultSecurityManager.hasPassword(this)) {
            // First time setup - launch atomic wizard
            val intent = Intent(this, SetupWizardActivity::class.java)
            startActivity(intent)
            finish()
            return
        }

        if (VaultSecurityManager.isVaultLockedOut(this)) {
            showLockoutAlert()
            return
        }

        // Unlock with user's configured lock type
        when (VaultSecurityManager.getLockType(this)) {
            VaultSecurityManager.LockType.PATTERN -> showUnlockPatternDialog()
            VaultSecurityManager.LockType.PIN -> showUnlockTextDialog(isPin = true)
            VaultSecurityManager.LockType.PASSWORD -> showUnlockTextDialog(isPin = false)
        }
    }

    private fun showLockoutAlert() {
        val remainingSec = (VaultSecurityManager.getVaultLockoutRemainingMs(this) / 1000) + 1
        AlertDialog.Builder(this)
            .setTitle("Vault Locked Out")
            .setMessage("Too many failed attempts. Vault authentication is locked for $remainingSec seconds.\n\nHardware monotonic timer is active. Rebooting will not reset this delay.")
            .setPositiveButton("OK", null)
            .setNegativeButton("RECOVER") { _, _ ->
                showPasswordRecoveryDialog()
            }
            .show()
    }

    // ---------------------------------------------------------------------------------------------
    // PATTERN LOCK UNLOCK
    // ---------------------------------------------------------------------------------------------

    private fun showUnlockPatternDialog() {
        if (isFinishing || isDestroyed) return
        activeDialog?.dismiss()

        val dialog = Dialog(this)
        dialog.setContentView(R.layout.dialog_pattern_lock)
        dialog.window?.let { window ->
            window.setBackgroundDrawableResource(android.R.color.transparent)
            window.setDimAmount(0.85f)
            val dialogWidth = (resources.displayMetrics.widthPixels * 0.92).toInt().coerceAtMost((380 * resources.displayMetrics.density).toInt())
            window.setLayout(dialogWidth, WindowManager.LayoutParams.WRAP_CONTENT)
        }

        val tvTitle = dialog.findViewById<TextView>(R.id.tvPatternTitle)
        val tvSubtitle = dialog.findViewById<TextView>(R.id.tvPatternSubtitle)
        val patternView = dialog.findViewById<PatternLockView>(R.id.patternLockView)
        val btnCancel = dialog.findViewById<Button>(R.id.btnCancelPattern)
        val btnForgot = dialog.findViewById<TextView>(R.id.btnForgotPasswordPattern)

        tvTitle.text = "Draw Vault Pattern"
        tvSubtitle.text = "Connect dots to unlock"

        btnForgot.visibility = View.VISIBLE
        btnForgot.setOnClickListener {
            dialog.dismiss()
            showPasswordRecoveryDialog()
        }

        btnCancel.setOnClickListener { dialog.dismiss() }

        patternView.onPatternListener = { pattern ->
            when (val result = VaultSecurityManager.checkPassword(this, pattern)) {
                VaultSecurityManager.PasswordCheckResult.REAL -> {
                    dialog.dismiss()
                    launchVaultGallery(isDecoy = false)
                }
                VaultSecurityManager.PasswordCheckResult.DECOY -> {
                    dialog.dismiss()
                    launchVaultGallery(isDecoy = true)
                }
                VaultSecurityManager.PasswordCheckResult.LOCKED_OUT -> {
                    patternView.setErrorState(true)
                    dialog.dismiss()
                    showLockoutAlert()
                }
                VaultSecurityManager.PasswordCheckResult.INVALID -> {
                    patternView.setErrorState(true)
                    Toast.makeText(this, "Wrong Pattern! Incorrect entry.", Toast.LENGTH_SHORT).show()
                    patternView.postDelayed({ patternView.resetPattern() }, 800)
                }
            }
        }

        activeDialog = dialog
        dialog.show()
    }

    // ---------------------------------------------------------------------------------------------
    // PIN & PASSWORD UNLOCK
    // ---------------------------------------------------------------------------------------------

    private fun showUnlockTextDialog(isPin: Boolean) {
        if (isFinishing || isDestroyed) return
        activeDialog?.dismiss()

        val dialog = Dialog(this)
        dialog.setContentView(R.layout.dialog_glass_password)
        dialog.window?.let { window ->
            window.setBackgroundDrawableResource(android.R.color.transparent)
            window.setDimAmount(0.85f)
            val dialogWidth = (resources.displayMetrics.widthPixels * 0.90).toInt().coerceAtMost((360 * resources.displayMetrics.density).toInt())
            window.setLayout(dialogWidth, WindowManager.LayoutParams.WRAP_CONTENT)
        }

        val dialogTitle = dialog.findViewById<TextView>(R.id.dialogTitle)
        val dialogInput = dialog.findViewById<EditText>(R.id.dialogInput)
        val dialogSetButton = dialog.findViewById<Button>(R.id.dialogSetButton)
        val btnForgot = dialog.findViewById<TextView>(R.id.btnForgotPasswordText)

        btnForgot.visibility = View.VISIBLE
        btnForgot.setOnClickListener {
            dialog.dismiss()
            showPasswordRecoveryDialog()
        }

        if (isPin) {
            dialogInput.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            dialogTitle.text = "Enter Vault PIN"
            dialogInput.hint = "Your PIN (6+ digits)"
        } else {
            dialogInput.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            dialogTitle.text = "Enter Vault Password"
            dialogInput.hint = "Your Password (8+ chars)"
        }
        dialogSetButton.text = "UNLOCK"

        dialogSetButton.setOnClickListener {
            val entered = dialogInput.text.toString().trim()

            when (VaultSecurityManager.checkPassword(this, entered)) {
                VaultSecurityManager.PasswordCheckResult.REAL -> {
                    dialog.dismiss()
                    launchVaultGallery(isDecoy = false)
                }
                VaultSecurityManager.PasswordCheckResult.DECOY -> {
                    dialog.dismiss()
                    launchVaultGallery(isDecoy = true)
                }
                VaultSecurityManager.PasswordCheckResult.LOCKED_OUT -> {
                    dialog.dismiss()
                    showLockoutAlert()
                }
                VaultSecurityManager.PasswordCheckResult.INVALID -> {
                    dialogInput.text.clear()
                    Toast.makeText(this, "Wrong ${if (isPin) "PIN" else "Password"}! Incorrect entry.", Toast.LENGTH_SHORT).show()
                }
            }
        }

        activeDialog = dialog
        dialog.show()
    }

    // ---------------------------------------------------------------------------------------------
    // PASSWORD RECOVERY / RESET DIALOG (OFFLINE RECOVERY CODE)
    // ---------------------------------------------------------------------------------------------

    private fun showPasswordRecoveryDialog() {
        if (VaultSecurityManager.isRecoveryLockedOut(this)) {
            val sec = (VaultSecurityManager.getRecoveryLockoutRemainingMs(this) / 1000) + 1
            Toast.makeText(this, "Recovery locked out. Please wait $sec seconds.", Toast.LENGTH_LONG).show()
            return
        }

        val dialog = Dialog(this)
        dialog.setContentView(R.layout.dialog_security_recovery)
        dialog.window?.let { window ->
            window.setBackgroundDrawableResource(android.R.color.transparent)
            window.setDimAmount(0.85f)
            val dialogWidth = (resources.displayMetrics.widthPixels * 0.90).toInt().coerceAtMost((360 * resources.displayMetrics.density).toInt())
            window.setLayout(dialogWidth, WindowManager.LayoutParams.WRAP_CONTENT)
        }

        val tvTitle = dialog.findViewById<TextView>(R.id.tvRecoveryTitle)
        val tvSubtitle = dialog.findViewById<TextView>(R.id.tvRecoverySubtitle)
        val tvQuestion = dialog.findViewById<TextView>(R.id.tvQuestionDisplay)
        val etAnswer = dialog.findViewById<EditText>(R.id.etRecoveryAnswer)
        val etNewPass = dialog.findViewById<EditText>(R.id.etNewPasswordReset)
        val btnCancel = dialog.findViewById<Button>(R.id.btnCancelRecovery)
        val btnSubmit = dialog.findViewById<Button>(R.id.btnSubmitRecovery)

        tvTitle.text = "Vault Recovery"
        tvSubtitle.text = "Enter your 16-character offline recovery code"
        tvQuestion.text = "Offline Master Recovery Code:"
        etAnswer.hint = "XXXX-XXXX-XXXX-XXXX"

        var verifiedRecoveryCode: String? = null

        btnSubmit.setOnClickListener {
            if (verifiedRecoveryCode == null) {
                val code = etAnswer.text.toString().trim()
                if (code.length < 10) {
                    Toast.makeText(this, "Please enter your 16-character recovery code.", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                // Verify recovery code by unwrapping master key
                val success = VaultSecurityManager.recoverWithRecoveryCode(this, code)
                if (success) {
                    verifiedRecoveryCode = code
                    etAnswer.visibility = View.GONE
                    etNewPass.visibility = View.VISIBLE
                    tvSubtitle.text = "Identity verified! Set your new vault credential."
                    tvQuestion.text = "Enter New Vault Key:"

                    val lockType = VaultSecurityManager.getLockType(this)
                    if (lockType == VaultSecurityManager.LockType.PIN) {
                        etNewPass.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
                        etNewPass.hint = "New Numeric PIN (6+ digits)"
                    } else {
                        etNewPass.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                        etNewPass.hint = "New Password (8+ chars)"
                    }
                    btnSubmit.text = "SAVE NEW KEY"
                    Toast.makeText(this, "Recovery Code Verified!", Toast.LENGTH_SHORT).show()
                } else {
                    if (VaultSecurityManager.isRecoveryLockedOut(this)) {
                        dialog.dismiss()
                        Toast.makeText(this, "Too many wrong attempts. Recovery locked.", Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(this, "Invalid Recovery Code!", Toast.LENGTH_SHORT).show()
                    }
                }
            } else {
                val newKey = etNewPass.text.toString().trim()
                val lockType = VaultSecurityManager.getLockType(this)
                val validation = if (lockType == VaultSecurityManager.LockType.PIN) {
                    CredentialPolicy.validatePin(newKey)
                } else {
                    CredentialPolicy.validatePassword(newKey)
                }
                if (validation is CredentialPolicy.ValidationResult.Invalid) {
                    Toast.makeText(this, validation.reason, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                val resetSuccess = VaultSecurityManager.resetVaultPasswordWithRecoveryCode(
                    context = this,
                    recoveryCode = verifiedRecoveryCode!!,
                    newKey = newKey
                )
                if (resetSuccess) {
                    Toast.makeText(this, "Vault password reset successfully!", Toast.LENGTH_SHORT).show()
                    dialog.dismiss()
                    launchVaultGallery(isDecoy = false)
                } else {
                    Toast.makeText(this, "Failed resetting password.", Toast.LENGTH_SHORT).show()
                }
            }
        }

        btnCancel.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun launchVaultGallery(isDecoy: Boolean) {
        val intent = Intent(this, VaultGalleryActivity::class.java).apply {
            putExtra("IS_DECOY", isDecoy)
        }
        startActivity(intent)
        finish()
    }
}
