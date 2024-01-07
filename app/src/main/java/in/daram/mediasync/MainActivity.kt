package `in`.daram.mediasync

import android.os.Bundle
import android.text.Editable
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity(), View.OnClickListener {
    lateinit var editHost: EditText
    lateinit var editUsername: EditText
    lateinit var editPassword: EditText

    lateinit var progress: ProgressBar
    lateinit var textNotice: TextView
    lateinit var textFile: TextView
    lateinit var buttonSync: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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

                val deleteList = compareFileListAsync(musicDirPath, fileList, hostText, m3u8List)
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
                    deleteFileAsync(file)
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
                    downloadMusicFileAsync(hostText, usernameText, passwordText, file, musicDirPath)
                    runOnUiThread {
                        ++progress.progress;
                    }
                }

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