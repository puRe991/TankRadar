package de.tankradar.app

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import de.tankradar.app.databinding.ActivitySetupBinding

/**
 * Asks for the address of the user's TankRadar server.
 *
 * Shown automatically on first start and reachable later from the toolbar menu,
 * for example when the PC running TankRadar gets a new IP address.
 */
class SetupActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySetupBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySetupBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.serverInput.setText(ServerConfig.getServerUrl(this).orEmpty())

        binding.saveButton.setOnClickListener {
            val normalized = ServerConfig.normalize(binding.serverInput.text?.toString().orEmpty())
            if (normalized == null) {
                binding.serverInputLayout.error = getString(R.string.setup_invalid_address)
                return@setOnClickListener
            }
            binding.serverInputLayout.error = null
            ServerConfig.setServerUrl(this, normalized)
            setResult(RESULT_OK)
            finish()
        }
    }
}
