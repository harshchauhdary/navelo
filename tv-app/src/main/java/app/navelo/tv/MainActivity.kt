package app.navelo.tv

import android.os.Bundle
import android.annotation.SuppressLint
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent

class MainActivity : ComponentActivity() {
    private lateinit var repository: TvRepository
    internal var playbackKeyHandler: ((KeyEvent) -> Boolean)? = null

    // Public Activity/Window.Callback hook. AndroidX Core annotates its inherited
    // implementation as restricted, but delegating unmatched keys to super is required.
    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        playbackKeyHandler?.invoke(event) == true || super.dispatchKeyEvent(event)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = TvRepository(applicationContext)
        repository.start()
        setContent {
            NaveloTheme {
                NaveloTvApp(repository = repository)
            }
        }
    }

    override fun onDestroy() {
        repository.close()
        super.onDestroy()
    }
}
