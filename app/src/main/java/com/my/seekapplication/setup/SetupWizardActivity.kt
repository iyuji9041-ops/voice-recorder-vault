package com.my.seekapplication.setup

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.my.seekapplication.PatternLockView
import com.my.seekapplication.R
import com.my.seekapplication.VaultGalleryActivity
import com.my.seekapplication.VaultSecurityManager
import com.my.seekapplication.crypto.CredentialPolicy

/**
 * SetupWizardActivity: Resumable, atomic 5-step vault security configuration wizard.
 *
 * Flow:
 * 1. Door Passcode (6+ digits)
 * 2. Real Vault Key (PIN, Password, or Pattern)
 * 3. Decoy Vault Key (Must differ from Real Key)
 * 4. Offline Recovery Code (16-char unwrap token + optional security question)
 * 5. Camera Permission (Upfront explanation for intruder selfies)
 * -> Atomic Commit: Vault remains locked and uninitialized until all steps commit.
 */
class SetupWizardActivity : AppCompatActivity() {

    private lateinit var tvStepIndicator: TextView

    // Step Containers
    private lateinit var containerStepDoor: LinearLayout
    private lateinit var containerStepReal: LinearLayout
    private lateinit var containerStepDecoy: LinearLayout
    private lateinit var containerStepRecovery: LinearLayout
    private lateinit var containerStepCamera: LinearLayout

    // Step 1: Door
    private lateinit var etDoorCode: EditText
    private lateinit var etDoorCodeConfirm: EditText
    private lateinit var btnNextDoor: Button

    // Step 2: Real Key
    private lateinit var btnTypePin: Button
    private lateinit var btnTypePassword: Button
    private lateinit var btnTypePattern: Button
    private lateinit var layoutRealTextInputs: LinearLayout
    private lateinit var layoutRealPatternInput: LinearLayout
    private lateinit var etRealKey: EditText
    private lateinit var etRealKeyConfirm: EditText
    private lateinit var patternViewReal: PatternLockView
    private lateinit var btnNextReal: Button
    private var selectedLockType = VaultSecurityManager.LockType.PIN
    private var enteredRealPattern: String? = null

    // Step 3: Decoy Key
    private lateinit var layoutDecoyTextInputs: LinearLayout
    private lateinit var layoutDecoyPatternInput: LinearLayout
    private lateinit var etDecoyKey: EditText
    private lateinit var etDecoyKeyConfirm: EditText
    private lateinit var patternViewDecoy: PatternLockView
    private lateinit var btnNextDecoy: Button
    private var enteredDecoyPattern: String? = null

    // Step 4: Recovery
    private lateinit var tvDisplayRecoveryCode: TextView
    private lateinit var btnCopyRecoveryCode: Button
    private lateinit var cbRecoverySaved: CheckBox
    private lateinit var etOptionalSecAnswer: EditText
    private lateinit var btnNextRecovery: Button
    private var generatedRecoveryCode = ""

    // Step 5: Camera
    private lateinit var btnGrantCamera: Button
    private lateinit var btnSkipCamera: Button

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        finishAndCommit()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        supportActionBar?.hide()
        enableEdgeToEdge()

        if (SetupWizardManager.isSetupCompleted(this)) {
            launchVault()
            return
        }

        setContentView(R.layout.activity_setup_wizard)

        val root = findViewById<LinearLayout>(R.id.rootSetupWizard)
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            root.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        bindViews()
        setupListeners()

        // Resume active stage
        val currentStage = SetupWizardManager.getCurrentStage(this)
        showStage(currentStage)
    }

    private fun bindViews() {
        tvStepIndicator = findViewById(R.id.tvStepIndicator)

        containerStepDoor = findViewById(R.id.containerStepDoor)
        containerStepReal = findViewById(R.id.containerStepReal)
        containerStepDecoy = findViewById(R.id.containerStepDecoy)
        containerStepRecovery = findViewById(R.id.containerStepRecovery)
        containerStepCamera = findViewById(R.id.containerStepCamera)

        // Step 1
        etDoorCode = findViewById(R.id.etDoorCode)
        etDoorCodeConfirm = findViewById(R.id.etDoorCodeConfirm)
        btnNextDoor = findViewById(R.id.btnNextDoor)

        // Step 2
        btnTypePin = findViewById(R.id.btnTypePin)
        btnTypePassword = findViewById(R.id.btnTypePassword)
        btnTypePattern = findViewById(R.id.btnTypePattern)
        layoutRealTextInputs = findViewById(R.id.layoutRealTextInputs)
        layoutRealPatternInput = findViewById(R.id.layoutRealPatternInput)
        etRealKey = findViewById(R.id.etRealKey)
        etRealKeyConfirm = findViewById(R.id.etRealKeyConfirm)
        patternViewReal = findViewById(R.id.patternViewReal)
        btnNextReal = findViewById(R.id.btnNextReal)

        // Step 3
        layoutDecoyTextInputs = findViewById(R.id.layoutDecoyTextInputs)
        layoutDecoyPatternInput = findViewById(R.id.layoutDecoyPatternInput)
        etDecoyKey = findViewById(R.id.etDecoyKey)
        etDecoyKeyConfirm = findViewById(R.id.etDecoyKeyConfirm)
        patternViewDecoy = findViewById(R.id.patternViewDecoy)
        btnNextDecoy = findViewById(R.id.btnNextDecoy)

        // Step 4
        tvDisplayRecoveryCode = findViewById(R.id.tvDisplayRecoveryCode)
        btnCopyRecoveryCode = findViewById(R.id.btnCopyRecoveryCode)
        cbRecoverySaved = findViewById(R.id.cbRecoverySaved)
        etOptionalSecAnswer = findViewById(R.id.etOptionalSecAnswer)
        btnNextRecovery = findViewById(R.id.btnNextRecovery)

        // Step 5
        btnGrantCamera = findViewById(R.id.btnGrantCamera)
        btnSkipCamera = findViewById(R.id.btnSkipCamera)
    }

    private fun setupListeners() {
        // --- Step 1: Door Passcode ---
        btnNextDoor.setOnClickListener {
            val code = etDoorCode.text.toString().trim()
            val confirm = etDoorCodeConfirm.text.toString().trim()

            val validation = CredentialPolicy.validateDoorPasscode(code)
            if (validation is CredentialPolicy.ValidationResult.Invalid) {
                Toast.makeText(this, validation.reason, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (!CredentialPolicy.constantTimeEquals(code, confirm)) {
                Toast.makeText(this, "Passcodes do not match!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            SetupWizardManager.stageDoorCode(this, code)
            showStage(SetupWizardManager.Stage.VAULT_REAL_KEY)
        }

        // --- Step 2: Lock Type Selector ---
        btnTypePin.setOnClickListener { selectLockType(VaultSecurityManager.LockType.PIN) }
        btnTypePassword.setOnClickListener { selectLockType(VaultSecurityManager.LockType.PASSWORD) }
        btnTypePattern.setOnClickListener { selectLockType(VaultSecurityManager.LockType.PATTERN) }

        patternViewReal.onPatternListener = { pattern ->
            enteredRealPattern = pattern
        }

        btnNextReal.setOnClickListener {
            val realKey = when (selectedLockType) {
                VaultSecurityManager.LockType.PIN,
                VaultSecurityManager.LockType.PASSWORD -> {
                    val key = etRealKey.text.toString().trim()
                    val confirm = etRealKeyConfirm.text.toString().trim()
                    val validation = if (selectedLockType == VaultSecurityManager.LockType.PIN) {
                        CredentialPolicy.validatePin(key)
                    } else {
                        CredentialPolicy.validatePassword(key)
                    }
                    if (validation is CredentialPolicy.ValidationResult.Invalid) {
                        Toast.makeText(this, validation.reason, Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    if (!CredentialPolicy.constantTimeEquals(key, confirm)) {
                        Toast.makeText(this, "Keys do not match!", Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    key
                }
                VaultSecurityManager.LockType.PATTERN -> {
                    val pattern = enteredRealPattern ?: ""
                    val validation = CredentialPolicy.validatePattern(pattern)
                    if (validation is CredentialPolicy.ValidationResult.Invalid) {
                        Toast.makeText(this, validation.reason, Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    pattern
                }
            }

            SetupWizardManager.stageRealKey(this, selectedLockType, realKey)
            showStage(SetupWizardManager.Stage.VAULT_DECOY_KEY)
        }

        // --- Step 3: Decoy Key ---
        patternViewDecoy.onPatternListener = { pattern ->
            enteredDecoyPattern = pattern
        }

        btnNextDecoy.setOnClickListener {
            val stagedRealKey = SetupWizardManager.getStagedRealKey(this) ?: ""
            val decoyKey = when (selectedLockType) {
                VaultSecurityManager.LockType.PIN,
                VaultSecurityManager.LockType.PASSWORD -> {
                    val key = etDecoyKey.text.toString().trim()
                    val confirm = etDecoyKeyConfirm.text.toString().trim()
                    val validation = if (selectedLockType == VaultSecurityManager.LockType.PIN) {
                        CredentialPolicy.validatePin(key)
                    } else {
                        CredentialPolicy.validatePassword(key)
                    }
                    if (validation is CredentialPolicy.ValidationResult.Invalid) {
                        Toast.makeText(this, validation.reason, Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    if (!CredentialPolicy.constantTimeEquals(key, confirm)) {
                        Toast.makeText(this, "Decoy keys do not match!", Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    key
                }
                VaultSecurityManager.LockType.PATTERN -> {
                    val pattern = enteredDecoyPattern ?: ""
                    val validation = CredentialPolicy.validatePattern(pattern)
                    if (validation is CredentialPolicy.ValidationResult.Invalid) {
                        Toast.makeText(this, validation.reason, Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    pattern
                }
            }

            if (CredentialPolicy.constantTimeEquals(decoyKey, stagedRealKey)) {
                Toast.makeText(this, "Decoy key cannot be identical to Real key!", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }

            SetupWizardManager.stageDecoyKey(this, decoyKey)
            showStage(SetupWizardManager.Stage.RECOVERY_CODE)
        }

        // --- Step 4: Recovery Code ---
        btnCopyRecoveryCode.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Vault Recovery Code", generatedRecoveryCode)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, "Recovery code copied to clipboard!", Toast.LENGTH_SHORT).show()
        }

        btnNextRecovery.setOnClickListener {
            if (!cbRecoverySaved.isChecked) {
                Toast.makeText(this, "Please check the box confirming you saved the recovery code.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val optionalAnswer = etOptionalSecAnswer.text.toString().trim().takeIf { it.isNotEmpty() }
            val question = if (optionalAnswer != null) "What was your first school or favorite keyword?" else null

            SetupWizardManager.stageRecoveryCode(
                context = this,
                code = generatedRecoveryCode,
                question = question,
                answer = optionalAnswer
            )
            showStage(SetupWizardManager.Stage.CAMERA_PERMISSION)
        }

        // --- Step 5: Camera Permission ---
        btnGrantCamera.setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                finishAndCommit()
            } else {
                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }

        btnSkipCamera.setOnClickListener {
            finishAndCommit()
        }
    }

    private fun selectLockType(lockType: VaultSecurityManager.LockType) {
        selectedLockType = lockType
        val activeBg = R.drawable.bg_glass_button_green
        val inactiveBg = R.drawable.bg_glass_input

        btnTypePin.setBackgroundResource(if (lockType == VaultSecurityManager.LockType.PIN) activeBg else inactiveBg)
        btnTypePin.setTextColor(if (lockType == VaultSecurityManager.LockType.PIN) Color.BLACK else Color.WHITE)

        btnTypePassword.setBackgroundResource(if (lockType == VaultSecurityManager.LockType.PASSWORD) activeBg else inactiveBg)
        btnTypePassword.setTextColor(if (lockType == VaultSecurityManager.LockType.PASSWORD) Color.BLACK else Color.WHITE)

        btnTypePattern.setBackgroundResource(if (lockType == VaultSecurityManager.LockType.PATTERN) activeBg else inactiveBg)
        btnTypePattern.setTextColor(if (lockType == VaultSecurityManager.LockType.PATTERN) Color.BLACK else Color.WHITE)

        updateInputViewsForLockType()
    }

    private fun updateInputViewsForLockType() {
        when (selectedLockType) {
            VaultSecurityManager.LockType.PIN -> {
                layoutRealTextInputs.visibility = View.VISIBLE
                layoutRealPatternInput.visibility = View.GONE
                layoutDecoyTextInputs.visibility = View.VISIBLE
                layoutDecoyPatternInput.visibility = View.GONE

                etRealKey.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
                etRealKey.hint = "6-Digit Real PIN"
                etRealKeyConfirm.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
                etRealKeyConfirm.hint = "Confirm Real PIN"

                etDecoyKey.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
                etDecoyKey.hint = "6-Digit Decoy PIN"
                etDecoyKeyConfirm.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
                etDecoyKeyConfirm.hint = "Confirm Decoy PIN"
            }
            VaultSecurityManager.LockType.PASSWORD -> {
                layoutRealTextInputs.visibility = View.VISIBLE
                layoutRealPatternInput.visibility = View.GONE
                layoutDecoyTextInputs.visibility = View.VISIBLE
                layoutDecoyPatternInput.visibility = View.GONE

                etRealKey.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                etRealKey.hint = "Real Password (8+ chars)"
                etRealKeyConfirm.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                etRealKeyConfirm.hint = "Confirm Real Password"

                etDecoyKey.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                etDecoyKey.hint = "Decoy Password (8+ chars)"
                etDecoyKeyConfirm.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                etDecoyKeyConfirm.hint = "Confirm Decoy Password"
            }
            VaultSecurityManager.LockType.PATTERN -> {
                layoutRealTextInputs.visibility = View.GONE
                layoutRealPatternInput.visibility = View.VISIBLE
                layoutDecoyTextInputs.visibility = View.GONE
                layoutDecoyPatternInput.visibility = View.VISIBLE
            }
        }
    }

    private fun showStage(stage: SetupWizardManager.Stage) {
        containerStepDoor.visibility = View.GONE
        containerStepReal.visibility = View.GONE
        containerStepDecoy.visibility = View.GONE
        containerStepRecovery.visibility = View.GONE
        containerStepCamera.visibility = View.GONE

        when (stage) {
            SetupWizardManager.Stage.NOT_STARTED,
            SetupWizardManager.Stage.DOOR_PASSCODE -> {
                tvStepIndicator.text = "Step 1 of 5"
                containerStepDoor.visibility = View.VISIBLE
            }
            SetupWizardManager.Stage.VAULT_REAL_KEY -> {
                tvStepIndicator.text = "Step 2 of 5"
                containerStepReal.visibility = View.VISIBLE
                selectedLockType = SetupWizardManager.getStagedLockType(this)
                selectLockType(selectedLockType)
            }
            SetupWizardManager.Stage.VAULT_DECOY_KEY -> {
                tvStepIndicator.text = "Step 3 of 5"
                containerStepDecoy.visibility = View.VISIBLE
                selectedLockType = SetupWizardManager.getStagedLockType(this)
                updateInputViewsForLockType()
            }
            SetupWizardManager.Stage.RECOVERY_CODE -> {
                tvStepIndicator.text = "Step 4 of 5"
                containerStepRecovery.visibility = View.VISIBLE
                generatedRecoveryCode = SetupWizardManager.getStagedRecoveryCode(this)
                tvDisplayRecoveryCode.text = generatedRecoveryCode
            }
            SetupWizardManager.Stage.CAMERA_PERMISSION -> {
                tvStepIndicator.text = "Step 5 of 5"
                containerStepCamera.visibility = View.VISIBLE
            }
            SetupWizardManager.Stage.COMPLETED -> {
                launchVault()
            }
        }
    }

    private fun finishAndCommit() {
        val success = SetupWizardManager.commitSetup(this)
        if (success) {
            Toast.makeText(this, "Vault Setup Complete! Opening Vault...", Toast.LENGTH_SHORT).show()
            launchVault()
        } else {
            Toast.makeText(this, "Error committing setup. Please try again.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun launchVault() {
        val intent = Intent(this, VaultGalleryActivity::class.java).apply {
            putExtra("IS_DECOY", false)
        }
        startActivity(intent)
        finish()
    }
}
