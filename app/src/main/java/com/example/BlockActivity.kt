package com.example

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.VideoView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.File
import android.util.Log
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.lifecycleScope
import coil.compose.AsyncImage
import com.example.data.AppDatabase
import com.example.data.BlockSchedule
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BlockActivity : ComponentActivity() {

    private var scheduleState = mutableStateOf<BlockSchedule?>(null)

    companion object {
        var isCurrentlyShowing = false
        const val EXTRA_SCHEDULE_ID = "extra_schedule_id"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        isCurrentlyShowing = true

        // Ensure full overlay window and flags to sit cleanly on lock screen or overlays
        window.addFlags(
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )

        val scheduleId = intent.getIntExtra(EXTRA_SCHEDULE_ID, -1)
        if (scheduleId != -1) {
            loadSchedule(scheduleId)
        }

        // Lock the Back button to send users cleanly back to the main launcher screen
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                redirectToHomeScreen()
            }
        })

        setContent {
            MyApplicationTheme {
                Surface(
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("block_activity_surface"),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val schedule by scheduleState
                    BlockScreenContent(
                        schedule = schedule,
                        onHomeClicked = { redirectToHomeScreen() }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        isCurrentlyShowing = true
    }

    override fun onPause() {
        super.onPause()
        isCurrentlyShowing = false
    }

    override fun onDestroy() {
        super.onDestroy()
        isCurrentlyShowing = false
    }

    private fun loadSchedule(id: Int) {
        lifecycleScope.launch {
            val db = AppDatabase.getDatabase(this@BlockActivity)
            val schedule = withContext(Dispatchers.IO) {
                db.blockScheduleDao().getScheduleById(id)
            }
            scheduleState.value = schedule
        }
    }

    private fun redirectToHomeScreen() {
        val homeIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(homeIntent)
    }
}

@Composable
fun BlockScreenContent(
    schedule: BlockSchedule?,
    onHomeClicked: () -> Unit
) {
    if (schedule == null) {
        DefaultBlockLayout(
            title = "Focused Period Active",
            message = "This app is currently blocked. Take a moment to focus on your real-world tasks!",
            onHomeClicked = onHomeClicked
        )
    } else {
        when (schedule.blockType.uppercase()) {
            "IMAGE" -> {
                ImageBlockLayout(
                    imageUrl = schedule.blockContent,
                    scheduleName = schedule.name,
                    onHomeClicked = onHomeClicked
                )
            }
            "WEBSITE" -> {
                WebsiteBlockLayout(
                    url = schedule.blockContent,
                    scheduleName = schedule.name,
                    onHomeClicked = onHomeClicked
                )
            }
            "VIDEO" -> {
                VideoBlockLayout(
                    videoUrl = schedule.blockContent,
                    scheduleName = schedule.name,
                    onHomeClicked = onHomeClicked
                )
            }
            "PDF" -> {
                PdfBlockLayout(
                    pdfUrl = schedule.blockContent,
                    scheduleName = schedule.name,
                    onHomeClicked = onHomeClicked
                )
            }
            else -> {
                DefaultBlockLayout(
                    title = schedule.name,
                    message = if (schedule.blockContent.isNotEmpty()) schedule.blockContent else "You blocked this application to preserve focus. Keep doing your best work!",
                    onHomeClicked = onHomeClicked
                )
            }
        }
    }
}

@Composable
fun DefaultBlockLayout(
    title: String,
    message: String,
    onHomeClicked: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Surface(
            modifier = Modifier.size(96.dp),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.primaryContainer
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Default.Lock,
                    contentDescription = "Lock Shield",
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
        
        Spacer(modifier = Modifier.height(32.dp))
        
        Text(
            text = title,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f),
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
        
        Spacer(modifier = Modifier.height(48.dp))
        
        Button(
            onClick = onHomeClicked,
            modifier = Modifier
                .fillMaxWidth(0.8f)
                .height(48.dp)
                .testTag("block_return_home_button"),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary
            )
        ) {
            Icon(Icons.Default.Home, contentDescription = "Home")
            Spacer(modifier = Modifier.width(8.dp))
            Text("Go to Home Screen", fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun ImageBlockLayout(
    imageUrl: String,
    scheduleName: String,
    onHomeClicked: () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        if (imageUrl.isNotEmpty()) {
            val model: Any = if (imageUrl.startsWith("http://") || imageUrl.startsWith("https://")) {
                imageUrl
            } else {
                File(imageUrl)
            }
            AsyncImage(
                model = model,
                contentDescription = "Motivational Banner",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } else {
            // Default elegant light background pattern
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.secondaryContainer)
            )
        }

        // Shadow tint overlay
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.5f))
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(20.dp))

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color.White.copy(alpha = 0.2f),
                    modifier = Modifier.padding(bottom = 12.dp)
                ) {
                    Text(
                        text = scheduleName,
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelLarge
                    )
                }

                Text(
                    text = "Aesthetic Block Mode Active",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 24.sp,
                    textAlign = TextAlign.Center
                )
            }

            Button(
                onClick = onHomeClicked,
                modifier = Modifier
                    .fillMaxWidth(0.81f)
                    .height(48.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.White,
                    contentColor = Color.Black
                )
            ) {
                Icon(Icons.Default.Home, contentDescription = "Home")
                Spacer(modifier = Modifier.width(8.dp))
                Text("Leave Blocked App", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun WebsiteBlockLayout(
    url: String,
    scheduleName: String,
    onHomeClicked: () -> Unit
) {
    val cleanUrl = if (url.startsWith("http://") || url.startsWith("https://")) url else "https://$url"

    Column(modifier = Modifier.fillMaxSize()) {
        // Small focused bar showing status info
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Language,
                        contentDescription = "Web Link Active",
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Website Focus: $scheduleName",
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        fontWeight = FontWeight.Medium,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                
                IconButton(onClick = onHomeClicked) {
                    Icon(
                        imageVector = Icons.Default.Home,
                        contentDescription = "Home",
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        }

        AndroidView(
            factory = { context ->
                WebView(context).apply {
                    webViewClient = WebViewClient()
                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                    }
                    loadUrl(cleanUrl)
                }
            },
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
fun VideoBlockLayout(
    videoUrl: String,
    scheduleName: String,
    onHomeClicked: () -> Unit
) {
    val context = LocalContext.current
    
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        if (videoUrl.isNotEmpty()) {
            val parsedUri = if (videoUrl.startsWith("http://") || videoUrl.startsWith("https://")) {
                Uri.parse(videoUrl)
            } else {
                Uri.fromFile(File(videoUrl))
            }
            AndroidView(
                factory = { ctx ->
                    VideoView(ctx).apply {
                        setVideoURI(parsedUri)
                        setOnPreparedListener { mp ->
                            mp.isLooping = true
                            start()
                        }
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        }

        // Overlay control block
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = Color.Black.copy(alpha = 0.6f)
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.padding(top = 16.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.PlayArrow,
                        contentDescription = "Video",
                        tint = Color.White,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Focus Video: $scheduleName",
                        color = Color.White,
                        fontWeight = FontWeight.Medium,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            Button(
                onClick = onHomeClicked,
                modifier = Modifier
                    .fillMaxWidth(0.8f)
                    .height(48.dp)
                    .padding(bottom = 8.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.White,
                    contentColor = Color.Black
                )
            ) {
                Icon(Icons.Default.Home, contentDescription = "Home")
                Spacer(modifier = Modifier.width(8.dp))
                Text("Exit Blocked App", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun PdfBlockLayout(
    pdfUrl: String,
    scheduleName: String,
    onHomeClicked: () -> Unit
) {
    val isOnline = pdfUrl.startsWith("http://") || pdfUrl.startsWith("https://")

    Column(modifier = Modifier.fillMaxSize()) {
        Surface(
            color = MaterialTheme.colorScheme.secondaryContainer,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.PictureAsPdf,
                        contentDescription = "PDF Active",
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Document Focus: $scheduleName",
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        fontWeight = FontWeight.Medium,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                
                IconButton(onClick = onHomeClicked) {
                    Icon(
                        imageVector = Icons.Default.Home,
                        contentDescription = "Home",
                        tint = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }
        }

        if (pdfUrl.isNotEmpty()) {
            if (isOnline) {
                val googleDocsPdfViewer = "https://docs.google.com/gview?embedded=true&url=" + Uri.encode(pdfUrl)
                AndroidView(
                    factory = { context ->
                        WebView(context).apply {
                            webViewClient = WebViewClient()
                            settings.apply {
                                javaScriptEnabled = true
                                domStorageEnabled = true
                            }
                            loadUrl(googleDocsPdfViewer)
                        }
                    },
                    modifier = Modifier.weight(1f)
                )
            } else {
                Box(modifier = Modifier.weight(1f)) {
                    LocalPdfRenderer(pdfUrl)
                }
            }
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No PDF File Selected.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                )
            }
        }
    }
}

@Composable
fun LocalPdfRenderer(filePath: String) {
    val file = File(filePath)
    if (!file.exists()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("PDF file not found.", style = MaterialTheme.typography.bodyMedium)
        }
        return
    }

    val bitmaps = remember(filePath) {
        val list = mutableListOf<Bitmap>()
        try {
            val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            val renderer = PdfRenderer(pfd)
            val pageCount = renderer.pageCount
            for (i in 0 until pageCount) {
                val page = renderer.openPage(i)
                val width = page.width * 2
                val height = page.height * 2
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                list.add(bitmap)
                page.close()
            }
            renderer.close()
            pfd.close()
        } catch (e: Exception) {
            Log.e("PdfRenderer", "Error rendering pdf: ${e.message}", e)
        }
        list
    }

    if (bitmaps.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Failed to render PDF document locally.", style = MaterialTheme.typography.bodyMedium)
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            items(bitmaps) { bitmap ->
                Card(
                    elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "PDF Page",
                        modifier = Modifier.fillMaxWidth().wrapContentHeight(),
                        contentScale = ContentScale.FillWidth
                    )
                }
            }
        }
    }
}
