package com.my.seekapplication

import android.app.Dialog
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.my.seekapplication.crypto.CredentialPolicy
import com.my.seekapplication.crypto.VaultKeyManager
import com.my.seekapplication.data.VaultBackupManager
import com.my.seekapplication.data.VaultRepository
import com.my.seekapplication.data.VaultSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * SettingsActivity: Vault configuration screen for changing lock types (PIN, Pattern, Password),
 * updating real/decoy keys, configuring secret trigger hold duration, door passcodes, auto-lock grace period,
 * security recovery, viewing offline recovery code, and encrypted backup (.svb).
 */
class SettingsActivity : BaseVaultActivity() {

    private lateinit var tvCurrentLockType: TextView
    private lateinit var tvRealKeyTitle: TextView
    private lateinit var tvFakeKeyTitle: TextView
    private lateinit var tvCurrentHoldDuration: TextView
    private lateinit var tvCurrentAutoLock: TextView

    private var tempRealKeySetup: String? = null
    private var pendingExportPassword = ""

    private val exportBackupLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        if (uri != null) {
            performExport(uri, pendingExportPassword)
        }
    }

    private val importBackupLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            promptImportPassword(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        enableEdgeToEdge()

        setContentView(R.layout.activity_settings)

        val root = findViewById<LinearLayout>(R.id.rootSettings)

        // Android 16 Window Insets: avoid edge squishing
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            root.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        val btnBack = findViewById<ImageView>(R.id.btnBackSettings)
        val btnChangeLockType = findViewById<LinearLayout>(R.id.btnChangeLockType)
        val btnChangeReal = findViewById<LinearLayout>(R.id.btnChangeRealPassword)
        val btnChangeFake = findViewById<LinearLayout>(R.id.btnChangeFakePassword)
        val btnChangeHold = findViewById<LinearLayout>(R.id.btnChangeHoldDuration)
        val btnChangeDoor = findViewById<LinearLayout>(R.id.btnChangeDoorPasscode)
        val btnChangeRecovery = findViewById<LinearLayout>(R.id.btnChangeRecoveryQuestion)
        val btnAutoLock = findViewById<LinearLayout>(R.id.btnAutoLock)
        val btnIntruderSelfie = findViewById<LinearLayout>(R.id.btnIntruderSelfie)
        val btnViewRecoveryCode = findViewById<LinearLayout>(R.id.btnViewRecoveryCode)
        val btnWipeVault = findViewById<LinearLayout>(R.id.btnWipeVault)

        tvCurrentLockType = findViewById(R.id.tvCurrentLockType)
        tvRealKeyTitle = findViewById(R.id.tvRealKeyTitle)
        tvFakeKeyTitle = findViewById(R.id.tvFakeKeyTitle)
        tvCurrentHoldDuration = findViewById(R.id.tvCurrentHoldDuration)
        tvCurrentAutoLock = findViewById(R.id.tvCurrentAutoLock)

        btnBack.setOnClickListener { finish() }

        // Decoy mode parity: Hide options that leak decoy / real duality
        if (VaultSession.isDecoy) {
            btnChangeFake.visibility = View.GONE
            btnIntruderSelfie.visibility = View.GONE
            btnViewRecoveryCode.visibility = View.GONE
            btnWipeVault.visibility = View.GONE
        }

        refreshLabels()

        btnChangeLockType.setOnClickListener {
            showChooseLockTypeDialog()
        }

        btnChangeReal.setOnClickListener {
            handleKeyUpdate(isReal = true)
        }

        btnChangeFake.setOnClickListener {
            handleKeyUpdate(isReal = false)
        }

        btnChangeHold.setOnClickListener {
            showHoldDurationDialog()
        }

        btnChangeDoor.setOnClickListener {
            showDoorPasscodeDialog()
        }

        btnChangeRecovery.setOnClickListener {
            showRecoveryQuestionDialog()
        }

        btnAutoLock.setOnClickListener {
            showAutoLockDialog()
        }

        btnIntruderSelfie.setOnClickListener {
            startActivity(Intent(this, IntruderListActivity::class.java))
        }

        btnViewRecoveryCode.setOnClickListener {
            promptViewRecoveryCode()
        }

        btnWipeVault.setOnClickListener {
            promptWipeVault()
        }

        val btnExportBackup = findViewById<LinearLayout>(R.id.btnExportBackup)
        btnExportBackup?.setOnClickListener {
            promptExportBackup()
        }

        val btnImportBackup = findViewById<LinearLayout>(R.id.btnImportBackup)
        btnImportBackup?.setOnClickListener {
            promptImportBackupFilePicker()
        }

        val btnPrivacyPolicy = findViewById<View>(R.id.btnPrivacyPolicy)
        btnPrivacyPolicy?.setOnClickListener {
            showPrivacyPolicyDialog()
        }
    }

    private fun showPrivacyPolicyDialog() {
        val policyUrl = "https://iyuji9041-ops.github.io/voice-recorder-vault/"
        AlertDialog.Builder(this)
            .setTitle("Privacy & Security Guarantee")
            .setMessage("100% OFFLINE ZERO-DATA POLICY\n\n• Zero Cloud Uploads: Your recordings and hidden vault files never leave your device.\n• AES-256-GCM Encryption: Photos, videos, and audios are locally encrypted.\n• Local Permissions Only: Camera and Microphone are used strictly on-device for recordings and security intruder selfies.\n\nOur full open-source privacy policy is available online.")
            .setPositiveButton("Open Web Policy") { _, _ ->
                try {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(policyUrl))
                    startActivity(intent)
                } catch (e: Exception) {
                    Toast.makeText(this, "Policy URL: $policyUrl", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun refreshLabels() {
        val currentLockType = VaultSecurityManager.getLockType(this)
        val typeName = when (currentLockType) {
            VaultSecurityManager.LockType.PIN -> "PIN (6+ Digits)"
            VaultSecurityManager.LockType.PATTERN -> "Pattern (5+ Dots)"
            VaultSecurityManager.LockType.PASSWORD -> "Password (8+ Chars)"
        }
        tvCurrentLockType.text = "Current: $typeName"
        if (VaultSession.isDecoy) {
            tvRealKeyTitle.text = "Change $typeName"
        } else {
            tvRealKeyTitle.text = "Change Real $typeName"
            tvFakeKeyTitle.text = "Change Decoy $typeName"
        }

        val seconds = VaultSecurityManager.getHoldDurationSeconds(this)
        tvCurrentHoldDuration.text = "Current: $seconds seconds"

        val autoLockSec = AutoLockManager.getGracePeriodSeconds(this)
        tvCurrentAutoLock.text = if (autoLockSec == 0) {
            "Current: Immediate"
        } else {
            "Current: $autoLockSec seconds"
        }
    }

    // ---------------------------------------------------------------------------------------------
    // AUTO-LOCK GRACE PERIOD PICKER
    // ---------------------------------------------------------------------------------------------

    private fun showAutoLockDialog() {
        val options = arrayOf("Immediate (Recommended)", "15 Seconds", "30 Seconds", "1 Minute", "5 Minutes")
        val values = intArrayOf(0, 15, 30, 60, 300)
        val currentSec = AutoLockManager.getGracePeriodSeconds(this)
        val checkedItem = values.indexOf(currentSec).takeIf { it >= 0 } ?: 0

        AlertDialog.Builder(this)
            .setTitle("Auto-Lock Grace Period")
            .setSingleChoiceItems(options, checkedItem) { dialog, which ->
                val chosenSec = values[which]
                AutoLockManager.setGracePeriodSeconds(this, chosenSec)
                refreshLabels()
                Toast.makeText(this, "Auto-lock set to ${options[which]}", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---------------------------------------------------------------------------------------------
    // SECRET HOLD DURATION PICKER
    // ---------------------------------------------------------------------------------------------

    private fun showHoldDurationDialog() {
        val options = arrayOf("5 Seconds (Default)", "7 Seconds", "10 Seconds", "15 Seconds", "20 Seconds")
        val values = intArrayOf(5, 7, 10, 15, 20)
        val currentSec = VaultSecurityManager.getHoldDurationSeconds(this)
        val checkedItem = values.indexOf(currentSec).takeIf { it >= 0 } ?: 0

        AlertDialog.Builder(this)
            .setTitle("Secret Trigger Hold Duration")
            .setSingleChoiceItems(options, checkedItem) { dialog, which ->
                val chosenSec = values[which]
                VaultSecurityManager.setHoldDurationSeconds(this, chosenSec)
                refreshLabels()
                Toast.makeText(this, "Hold duration set to $chosenSec seconds.", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---------------------------------------------------------------------------------------------
    // DOOR PASSCODE SETTING (LAYER 1)
    // ---------------------------------------------------------------------------------------------

    private fun showDoorPasscodeDialog() {
        val dialog = Dialog(this)
        dialog.setContentView(R.layout.dialog_door_passcode)
        dialog.window?.let { window ->
            window.setBackgroundDrawableResource(android.R.color.transparent)
            window.setDimAmount(0.85f)
            val dialogWidth = (resources.displayMetrics.widthPixels * 0.90).toInt().coerceAtMost((360 * resources.displayMetrics.density).toInt())
            window.setLayout(dialogWidth, WindowManager.LayoutParams.WRAP_CONTENT)
        }

        val tvTitle = dialog.findViewById<TextView>(R.id.tvDoorTitle)
        val tvSubtitle = dialog.findViewById<TextView>(R.id.tvDoorSubtitle)
        val etInput = dialog.findViewById<EditText>(R.id.etDoorPasscodeInput)
        val btnForgot = dialog.findViewById<TextView>(R.id.btnDoorForgotPassword)
        val btnCancel = dialog.findViewById<Button>(R.id.btnCancelDoor)
        val btnSubmit = dialog.findViewById<Button>(R.id.btnSubmitDoor)

        tvTitle.text = "Update Door Passcode"
        tvSubtitle.text = "Set new 6-digit code for Layer 1 entrance"
        btnForgot.visibility = View.GONE

        btnCancel.setOnClickListener { dialog.dismiss() }

        btnSubmit.setOnClickListener {
            val code = etInput.text.toString().trim()
            val validation = CredentialPolicy.validateDoorPasscode(code)
            if (validation is CredentialPolicy.ValidationResult.Invalid) {
                Toast.makeText(this, validation.reason, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            VaultSecurityManager.setDoorPasscode(this, code)
            dialog.dismiss()
            Toast.makeText(this, "Door Passcode updated successfully.", Toast.LENGTH_SHORT).show()
        }

        dialog.show()
    }

    // ---------------------------------------------------------------------------------------------
    // RECOVERY QUESTION SETUP
    // ---------------------------------------------------------------------------------------------

    private fun showRecoveryQuestionDialog() {
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
        val btnCancel = dialog.findViewById<Button>(R.id.btnCancelRecovery)
        val btnSubmit = dialog.findViewById<Button>(R.id.btnSubmitRecovery)

        tvTitle.text = "Security Recovery Setup"
        tvSubtitle.text = "Used as an extra factor to reset passwords"
        tvQuestion.text = "Security Question:\nWhat was your first school or favorite keyword?"
        etAnswer.hint = "Your secret recovery answer"
        btnSubmit.text = "SAVE ANSWER"

        btnCancel.setOnClickListener { dialog.dismiss() }

        btnSubmit.setOnClickListener {
            val ans = etAnswer.text.toString().trim()
            if (ans.length < 3) {
                Toast.makeText(this, "Answer must be at least 3 characters!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            VaultSecurityManager.setSecurityRecovery(this, "What was your first school or favorite keyword?", ans)
            dialog.dismiss()
            Toast.makeText(this, "Recovery question saved successfully.", Toast.LENGTH_SHORT).show()
        }

        dialog.show()
    }

    // ---------------------------------------------------------------------------------------------
    // SWITCH LOCK TYPE DIALOG
    // ---------------------------------------------------------------------------------------------

    private fun showChooseLockTypeDialog() {
        val dialog = Dialog(this)
        dialog.setContentView(R.layout.dialog_choose_lock_type)
        dialog.window?.let { window ->
            window.setBackgroundDrawableResource(android.R.color.transparent)
            window.setDimAmount(0.85f)
            val dialogWidth = (resources.displayMetrics.widthPixels * 0.90).toInt().coerceAtMost((360 * resources.displayMetrics.density).toInt())
            window.setLayout(dialogWidth, WindowManager.LayoutParams.WRAP_CONTENT)
        }

        dialog.findViewById<View>(R.id.btnSelectPin).setOnClickListener {
            dialog.dismiss()
            promptSwitchLockType(VaultSecurityManager.LockType.PIN)
        }

        dialog.findViewById<View>(R.id.btnSelectPattern).setOnClickListener {
            dialog.dismiss()
            promptSwitchLockType(VaultSecurityManager.LockType.PATTERN)
        }

        dialog.findViewById<View>(R.id.btnSelectPassword).setOnClickListener {
            dialog.dismiss()
            promptSwitchLockType(VaultSecurityManager.LockType.PASSWORD)
        }

        dialog.show()
    }

    private fun promptSwitchLockType(targetType: VaultSecurityManager.LockType) {
        val currentType = VaultSecurityManager.getLockType(this)
        if (targetType == currentType) {
            Toast.makeText(this, "Vault is already using this lock type.", Toast.LENGTH_SHORT).show()
            return
        }

        when (targetType) {
            VaultSecurityManager.LockType.PATTERN -> {
                showSetupPatternDialog(isReal = true, onComplete = {
                    VaultSecurityManager.setLockType(this, VaultSecurityManager.LockType.PATTERN)
                    refreshLabels()
                    Toast.makeText(this, "Switched to Pattern Lock successfully.", Toast.LENGTH_SHORT).show()
                })
            }
            VaultSecurityManager.LockType.PIN -> {
                showSetupTextDialog(isReal = true, isPin = true, onComplete = {
                    VaultSecurityManager.setLockType(this, VaultSecurityManager.LockType.PIN)
                    refreshLabels()
                    Toast.makeText(this, "Switched to PIN Lock successfully.", Toast.LENGTH_SHORT).show()
                })
            }
            VaultSecurityManager.LockType.PASSWORD -> {
                showSetupTextDialog(isReal = true, isPin = false, onComplete = {
                    VaultSecurityManager.setLockType(this, VaultSecurityManager.LockType.PASSWORD)
                    refreshLabels()
                    Toast.makeText(this, "Switched to Password Lock successfully.", Toast.LENGTH_SHORT).show()
                })
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // KEY UPDATE HANDLERS FOR CURRENT LOCK TYPE
    // ---------------------------------------------------------------------------------------------

    private fun handleKeyUpdate(isReal: Boolean) {
        val lockType = VaultSecurityManager.getLockType(this)
        if (lockType == VaultSecurityManager.LockType.PATTERN) {
            showChangePatternDialog(isReal)
        } else {
            showChangeTextDialog(isReal, isPin = (lockType == VaultSecurityManager.LockType.PIN))
        }
    }

    private fun showChangePatternDialog(isReal: Boolean) {
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

        tvTitle.text = if (isReal) "Set New Real Pattern" else "Set New Decoy Pattern"
        tvSubtitle.text = "Connect at least 5 dots"

        btnCancel.setOnClickListener { dialog.dismiss() }

        patternView.onPatternListener = { pattern ->
            val validation = CredentialPolicy.validatePattern(pattern)
            if (validation is CredentialPolicy.ValidationResult.Invalid) {
                Toast.makeText(this, validation.reason, Toast.LENGTH_SHORT).show()
                patternView.setErrorState(true)
                patternView.postDelayed({ patternView.resetPattern() }, 600)
            } else {
                val oppositeResult = VaultSecurityManager.checkPassword(this, pattern)
                if (isReal && oppositeResult == VaultSecurityManager.PasswordCheckResult.DECOY) {
                    Toast.makeText(this, "Real pattern cannot match current Decoy pattern!", Toast.LENGTH_LONG).show()
                    patternView.setErrorState(true)
                    patternView.postDelayed({ patternView.resetPattern() }, 600)
                } else if (!isReal && oppositeResult == VaultSecurityManager.PasswordCheckResult.REAL) {
                    Toast.makeText(this, "Decoy pattern cannot match current Real pattern!", Toast.LENGTH_LONG).show()
                    patternView.setErrorState(true)
                    patternView.postDelayed({ patternView.resetPattern() }, 600)
                } else {
                    VaultSecurityManager.setPassword(this, pattern, isReal)
                    dialog.dismiss()
                    Toast.makeText(this, "Pattern Updated Successfully.", Toast.LENGTH_SHORT).show()
                }
            }
        }
        dialog.show()
    }

    private fun showChangeTextDialog(isReal: Boolean, isPin: Boolean) {
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

        val keyTypeName = if (isPin) "PIN" else "Password"
        dialogTitle.text = if (isReal) "Set New Real $keyTypeName" else "Set New Decoy $keyTypeName"
        dialogSetButton.text = "UPDATE $keyTypeName"

        if (isPin) {
            dialogInput.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            dialogInput.hint = "New Numeric PIN (6+ digits)"
        } else {
            dialogInput.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            dialogInput.hint = "New Password (8+ characters)"
        }

        dialogSetButton.setOnClickListener {
            val newKey = dialogInput.text.toString().trim()
            val validation = if (isPin) CredentialPolicy.validatePin(newKey) else CredentialPolicy.validatePassword(newKey)
            if (validation is CredentialPolicy.ValidationResult.Invalid) {
                Toast.makeText(this, validation.reason, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val oppositeResult = VaultSecurityManager.checkPassword(this, newKey)
            if (isReal && oppositeResult == VaultSecurityManager.PasswordCheckResult.DECOY) {
                Toast.makeText(this, "Real $keyTypeName cannot match current Decoy $keyTypeName!", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            if (!isReal && oppositeResult == VaultSecurityManager.PasswordCheckResult.REAL) {
                Toast.makeText(this, "Decoy $keyTypeName cannot match current Real $keyTypeName!", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }

            VaultSecurityManager.setPassword(this, newKey, isReal)
            dialog.dismiss()
            Toast.makeText(this, "$keyTypeName Updated Successfully.", Toast.LENGTH_SHORT).show()
        }
        dialog.show()
    }

    // ---------------------------------------------------------------------------------------------
    // HELPERS FOR SETTING UP KEYS WHEN SWITCHING LOCK TYPES
    // ---------------------------------------------------------------------------------------------

    private fun showSetupPatternDialog(isReal: Boolean, onComplete: () -> Unit) {
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

        if (isReal) {
            tvTitle.text = "Set NEW Real Pattern"
            tvSubtitle.text = "Connect at least 5 dots"
        } else {
            tvTitle.text = "Set NEW Decoy Pattern"
            tvSubtitle.text = "Draw a DIFFERENT pattern (5+ dots)"
        }

        btnCancel.setOnClickListener { dialog.dismiss() }

        patternView.onPatternListener = { pattern ->
            val validation = CredentialPolicy.validatePattern(pattern)
            if (validation is CredentialPolicy.ValidationResult.Invalid) {
                Toast.makeText(this, validation.reason, Toast.LENGTH_SHORT).show()
                patternView.setErrorState(true)
                patternView.postDelayed({ patternView.resetPattern() }, 600)
            } else {
                if (isReal) {
                    tempRealKeySetup = pattern
                    VaultSecurityManager.setPassword(this, pattern, isReal = true)
                    dialog.dismiss()
                    Toast.makeText(this, "Real Pattern Saved. Now set Decoy Pattern.", Toast.LENGTH_SHORT).show()
                    showSetupPatternDialog(isReal = false, onComplete = onComplete)
                } else {
                    if (CredentialPolicy.constantTimeEquals(pattern, tempRealKeySetup ?: "")) {
                        Toast.makeText(this, "Decoy pattern cannot match Real pattern!", Toast.LENGTH_LONG).show()
                        patternView.setErrorState(true)
                        patternView.postDelayed({ patternView.resetPattern() }, 600)
                    } else {
                        VaultSecurityManager.setPassword(this, pattern, isReal = false)
                        tempRealKeySetup = null
                        dialog.dismiss()
                        onComplete()
                    }
                }
            }
        }
        dialog.show()
    }

    private fun showSetupTextDialog(isReal: Boolean, isPin: Boolean, onComplete: () -> Unit) {
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

        val keyTypeName = if (isPin) "PIN" else "Password"

        if (isPin) {
            dialogInput.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            dialogTitle.text = if (isReal) "Set REAL Numeric PIN" else "Set DECOY Numeric PIN"
            dialogInput.hint = if (isReal) "6-Digit Real PIN" else "6-Digit Decoy PIN"
        } else {
            dialogInput.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            dialogTitle.text = if (isReal) "Set REAL Password" else "Set DECOY Password"
            dialogInput.hint = if (isReal) "Real Password (8+ chars)" else "Decoy Password (8+ chars)"
        }
        dialogSetButton.text = "SAVE"

        dialogSetButton.setOnClickListener {
            val key = dialogInput.text.toString().trim()
            val validation = if (isPin) CredentialPolicy.validatePin(key) else CredentialPolicy.validatePassword(key)
            if (validation is CredentialPolicy.ValidationResult.Invalid) {
                Toast.makeText(this, validation.reason, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (isReal) {
                tempRealKeySetup = key
                VaultSecurityManager.setPassword(this, key, isReal = true)
                dialog.dismiss()
                Toast.makeText(this, "Real $keyTypeName Saved. Now set Decoy $keyTypeName.", Toast.LENGTH_SHORT).show()
                showSetupTextDialog(isReal = false, isPin = isPin, onComplete = onComplete)
            } else {
                if (CredentialPolicy.constantTimeEquals(key, tempRealKeySetup ?: "")) {
                    Toast.makeText(this, "Decoy key cannot be identical to Real key!", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }

                VaultSecurityManager.setPassword(this, key, isReal = false)
                tempRealKeySetup = null
                dialog.dismiss()
                onComplete()
            }
        }
        dialog.show()
    }

    // ---------------------------------------------------------------------------------------------
    // OFFLINE RECOVERY CODE & FACTORY WIPE
    // ---------------------------------------------------------------------------------------------

    private fun promptViewRecoveryCode() {
        val dialog = Dialog(this)
        dialog.setContentView(R.layout.dialog_glass_password)
        dialog.window?.let { window ->
            window.setBackgroundDrawableResource(android.R.color.transparent)
            window.setDimAmount(0.85f)
            val dialogWidth = (resources.displayMetrics.widthPixels * 0.90).toInt().coerceAtMost((360 * resources.displayMetrics.density).toInt())
            window.setLayout(dialogWidth, WindowManager.LayoutParams.WRAP_CONTENT)
        }

        val tvTitle = dialog.findViewById<TextView>(R.id.dialogTitle)
        val etInput = dialog.findViewById<EditText>(R.id.dialogInput)
        val btnSubmit = dialog.findViewById<Button>(R.id.dialogSetButton)
        val btnForgot = dialog.findViewById<TextView>(R.id.btnForgotPasswordText)
        btnForgot.visibility = View.GONE

        tvTitle.text = "Authenticate to View Recovery Code"
        etInput.hint = "Enter your Real Vault Key"
        val isPin = VaultSecurityManager.getLockType(this) == VaultSecurityManager.LockType.PIN
        if (isPin) {
            etInput.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        } else {
            etInput.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        btnSubmit.text = "VIEW CODE"

        btnSubmit.setOnClickListener {
            val key = etInput.text.toString().trim()
            val check = VaultSecurityManager.checkPassword(this, key)
            if (check == VaultSecurityManager.PasswordCheckResult.REAL) {
                dialog.dismiss()
                val code = com.my.seekapplication.setup.SetupWizardManager.getStagedRecoveryCode(this)
                AlertDialog.Builder(this)
                    .setTitle("Your Offline Recovery Code")
                    .setMessage("Recovery Code:\n\n$code\n\nStore this code securely. It is the only way to recover your vault without your master password.")
                    .setPositiveButton("DONE", null)
                    .show()
            } else {
                Toast.makeText(this, "Incorrect password!", Toast.LENGTH_SHORT).show()
            }
        }
        dialog.show()
    }

    private fun promptWipeVault() {
        AlertDialog.Builder(this)
            .setTitle("⚠️ Factory Reset / Wipe Vault")
            .setMessage("DESTRUCTIVE ACTION:\n\nThis will permanently delete all encrypted files, vault keys, intruder selfie logs, and settings.\n\nThis cannot be undone. Are you absolutely certain?")
            .setPositiveButton("PERMANENTLY WIPE") { _, _ ->
                val wiped = VaultSecurityManager.wipeVaultCompletely(this)
                if (wiped) {
                    Toast.makeText(this, "Vault wiped completely.", Toast.LENGTH_LONG).show()
                    val intent = Intent(this, VoiceRecorderActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    }
                    startActivity(intent)
                    finish()
                } else {
                    Toast.makeText(this, "Error wiping vault.", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---------------------------------------------------------------------------------------------
    // ENCRYPTED BACKUP (.svb) EXPORT & IMPORT
    // ---------------------------------------------------------------------------------------------

    private fun promptExportBackup() {
        val repo = VaultSession.getRepositoryOrNull()
        if (repo == null) {
            Toast.makeText(this, "Active vault session expired.", Toast.LENGTH_SHORT).show()
            return
        }

        val dialog = Dialog(this)
        dialog.setContentView(R.layout.dialog_glass_password)
        dialog.window?.let { window ->
            window.setBackgroundDrawableResource(android.R.color.transparent)
            window.setDimAmount(0.85f)
            val dialogWidth = (resources.displayMetrics.widthPixels * 0.90).toInt().coerceAtMost((360 * resources.displayMetrics.density).toInt())
            window.setLayout(dialogWidth, WindowManager.LayoutParams.WRAP_CONTENT)
        }

        val tvTitle = dialog.findViewById<TextView>(R.id.dialogTitle)
        val etInput = dialog.findViewById<EditText>(R.id.dialogInput)
        val btnSubmit = dialog.findViewById<Button>(R.id.dialogSetButton)
        val btnForgot = dialog.findViewById<TextView>(R.id.btnForgotPasswordText)
        btnForgot.visibility = View.GONE

        tvTitle.text = "Set Backup Password"
        etInput.hint = "Password to encrypt backup (.svb)"
        etInput.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        btnSubmit.text = "CHOOSE SAVE LOCATION"

        btnSubmit.setOnClickListener {
            val pass = etInput.text.toString().trim()
            if (pass.length < 6) {
                Toast.makeText(this, "Backup password must be at least 6 characters!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            pendingExportPassword = pass
            dialog.dismiss()
            exportBackupLauncher.launch("vault_backup_${System.currentTimeMillis()}.svb")
        }

        dialog.show()
    }

    private fun performExport(destUri: Uri, pass: String) {
        val repo = VaultSession.getRepositoryOrNull() ?: return
        Toast.makeText(this, "Exporting encrypted backup...", Toast.LENGTH_SHORT).show()

        lifecycleScope.launch {
            val count = withContext(Dispatchers.IO) {
                VaultBackupManager.exportBackup(this@SettingsActivity, repo, destUri, pass)
            }
            if (count >= 0) {
                Toast.makeText(this@SettingsActivity, "Backup exported successfully ($count items)!", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this@SettingsActivity, "Failed exporting backup.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun promptImportBackupFilePicker() {
        importBackupLauncher.launch(arrayOf("*/*"))
    }

    private fun promptImportPassword(sourceUri: Uri) {
        val repo = VaultSession.getRepositoryOrNull()
        if (repo == null) {
            Toast.makeText(this, "Active vault session expired.", Toast.LENGTH_SHORT).show()
            return
        }

        val dialog = Dialog(this)
        dialog.setContentView(R.layout.dialog_glass_password)
        dialog.window?.let { window ->
            window.setBackgroundDrawableResource(android.R.color.transparent)
            window.setDimAmount(0.85f)
            val dialogWidth = (resources.displayMetrics.widthPixels * 0.90).toInt().coerceAtMost((360 * resources.displayMetrics.density).toInt())
            window.setLayout(dialogWidth, WindowManager.LayoutParams.WRAP_CONTENT)
        }

        val tvTitle = dialog.findViewById<TextView>(R.id.dialogTitle)
        val etInput = dialog.findViewById<EditText>(R.id.dialogInput)
        val btnSubmit = dialog.findViewById<Button>(R.id.dialogSetButton)
        val btnForgot = dialog.findViewById<TextView>(R.id.btnForgotPasswordText)
        btnForgot.visibility = View.GONE

        tvTitle.text = "Enter Backup Password"
        etInput.hint = "Password used when exporting"
        etInput.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        btnSubmit.text = "IMPORT BACKUP"

        btnSubmit.setOnClickListener {
            val pass = etInput.text.toString().trim()
            if (pass.isEmpty()) {
                Toast.makeText(this, "Password required!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            dialog.dismiss()
            performImport(sourceUri, pass, repo)
        }

        dialog.show()
    }

    private fun performImport(sourceUri: Uri, pass: String, repo: VaultRepository) {
        Toast.makeText(this, "Decrypting and restoring backup...", Toast.LENGTH_SHORT).show()

        lifecycleScope.launch {
            val count = withContext(Dispatchers.IO) {
                VaultBackupManager.importBackup(this@SettingsActivity, sourceUri, pass, repo)
            }
            if (count >= 0) {
                Toast.makeText(this@SettingsActivity, "Restored $count items into vault!", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this@SettingsActivity, "Failed restoring backup: incorrect password or invalid file.", Toast.LENGTH_LONG).show()
            }
        }
    }
}
