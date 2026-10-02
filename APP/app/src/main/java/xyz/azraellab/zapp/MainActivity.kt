package xyz.azraellab.zapp

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import xyz.azraellab.zapp.core.AndroidCtx
import xyz.azraellab.zapp.core.installPresetFileBridge

/**
 * Единственная Activity.
 *
 * Кроме композиции здесь ещё две вещи, которых нельзя сделать из common-кода:
 * передача контекста в хранилище и регистрация launcher'ов SAF. `registerForActivityResult`
 * должен быть вызван до `onCreate`, иначе Activity выбросит -- отсюда инициализация
 * полей, а не создание лаунчеров внутри колбэка.
 */
class MainActivity : ComponentActivity() {

    /** Результат чтения пресета: продолжение, ожидающее URI, и само оно. */
    private var pendingRead: ((Uri?) -> Unit)? = null
    private var pendingWrite: ((Uri?) -> Unit)? = null

    private val readLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            pendingRead?.invoke(uri)
            pendingRead = null
        }

    private val writeLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            pendingWrite?.invoke(uri)
            pendingWrite = null
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        AndroidCtx.attach(this)

        // Мост устанавливается до первого Composable: иначе кнопки импорта и
        // экспорта на странице настроек увидят `available = false` до того,
        // как кто-то успеет их нажать.
        installPresetFileBridge(
            context = this,
            onPickRead = { onResult ->
                pendingRead = onResult
                readLauncher.launch(arrayOf("application/json", "application/octet-stream", "*/*"))
            },
            onPickWrite = { name, onResult ->
                pendingWrite = onResult
                writeLauncher.launch(name.ifBlank { "zapp-preset.json" })
            }
        )

        setContent {
            App()
        }
    }
}
