package `in`.daram.mediasync

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity(), View.OnClickListener {
    private lateinit var editHost: EditText
    private lateinit var editUsername: EditText
    private lateinit var editPassword: EditText

    private lateinit var progress: ProgressBar
    private lateinit var textNotice: TextView
    private lateinit var textFile: TextView
    private lateinit var buttonSync: Button

    private lateinit var sharedPreferences: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        sharedPreferences = getPreferences(Context.MODE_PRIVATE)

        requestPermissions(this)

        setContentView(R.layout.main_layout)

        editHost = findViewById(R.id.edit_host)
        editUsername = findViewById(R.id.edit_username)
        editPassword = findViewById(R.id.edit_password)

        progress = findViewById(R.id.progress)
        textNotice = findViewById(R.id.text_notice)
        textFile = findViewById(R.id.text_file)
        buttonSync = findViewById(R.id.sync_button)

        progress.min = 0
        progress.max = 1
        progress.progress = 0
        progress.isIndeterminate = false

        val savedHost = sharedPreferences.getString("HOST", null)
        if (savedHost != null) {
            editHost.text.clear()
            editHost.text.insert(0, savedHost)
        }

        val savedUsername = sharedPreferences.getString("USERNAME", null)
        if (savedUsername != null) {
            editUsername.text.insert(0, savedUsername)
        }

        val savedPassword = sharedPreferences.getString("PASSWORD", null)
        if (savedPassword != null) {
            editPassword.text.insert(0, savedPassword)
        }

        buttonSync.setOnClickListener(this)
    }

    override fun onClick(view: View?) {
        if (view != buttonSync)
            return

        if (!checkPermissions(this)) {
            textNotice.text = getString(R.string.notice_failed_because_no_permission)
            return
        }

        setState(false)
        progress.progress = 0
        progress.isIndeterminate = true

        textNotice.text = getString(R.string.notice_initializing)
        textFile.text = ""

        val hostText = editHost.text.toString()
        val usernameText = editUsername.text.toString()
        val passwordText = editPassword.text.toString()

        with (sharedPreferences.edit()) {
            putString("HOST", hostText)
            putString("USERNAME", usernameText)
            putString("PASSWORD", passwordText)
            apply()
        }

        CoroutineScope(Dispatchers.IO).launch {
            textNotice.text = getString(R.string.notice_connecting_to_host)

            try {
                val connection = connectToMusicListFileAsync(hostText, usernameText, passwordText)
                if (connection == null) {
                    runOnUiThread {
                        textNotice.text = getString(R.string.notice_failed_connect_to_host)
                    }
                    return@launch
                }

                runOnUiThread {
                    progress.isIndeterminate = true
                    textNotice.text = getString(R.string.notice_receiving_filelist)
                }
                val m3u8List = downloadMusicListFileAsync(hostText, connection)
                if (m3u8List.isEmpty()) {
                    runOnUiThread {
                        textNotice.text = getString(R.string.notice_failed_download_playlist)
                    }
                    return@launch
                }

                runOnUiThread {
                    progress.isIndeterminate = true
                    textNotice.text = getString(R.string.notice_compare_to_filelist)
                }
                val musicDirPath = getMusicFolderPath(this@MainActivity) ?: return@launch
                val fileList = getMusicFolderFiles(this@MainActivity)

                Log.i("MediaSync", "Compare File list for delete local storage...")
                val deleteList = compareFileListAsync(musicDirPath, fileList, hostText, m3u8List)
                Log.i("MediaSync", "Compare File list for download from network...")
                val downloadList = compareFileListAsync(hostText, m3u8List, musicDirPath, fileList)

                runOnUiThread {
                    textFile.text = ""
                    textNotice.text = getString(R.string.notice_delete_files)
                    progress.isIndeterminate = false
                    progress.min = 0
                    progress.max = deleteList.size
                    progress.progress = 0
                }
                for (file in deleteList) {
                    runOnUiThread {
                        textFile.text = file
                    }
                    Log.i("MediaSync", "Trying to delete file: $file")
                    val succeed = deleteFileAsync(file)
                    if (succeed) {
                        Log.i("MediaSync", "SUCCEED")
                    } else {
                        Log.e("MediaSync", "FAILED")
                    }
                    runOnUiThread {
                        ++progress.progress;
                    }
                }

                runOnUiThread {
                    textFile.text = ""
                    textNotice.text = getString(R.string.notice_downloading_files)
                    progress.isIndeterminate = false
                    progress.min = 0
                    progress.max = downloadList.size
                    progress.progress = 0
                }
                for (file in downloadList) {
                    runOnUiThread {
                        textFile.text = file
                    }
                    Log.i("MediaSync", "Trying to download file: $file")
                    downloadMusicFileAsync(hostText, usernameText, passwordText, file, musicDirPath)
                    runOnUiThread {
                        ++progress.progress;
                    }
                }

                Log.i("MediaSync", "Done.")

                runOnUiThread {
                    textNotice.text = getString(R.string.notice_done)
                }
            } finally {
                runOnUiThread {
                    setState(true)
                    textFile.text = ""
                    progress.progress = 0
                    progress.isIndeterminate = false
                }
            }
        }
    }

    private fun setState(on: Boolean) {
        editHost.isEnabled = on
        editUsername.isEnabled = on
        editPassword.isEnabled = on
        buttonSync.isEnabled = on
    }
}