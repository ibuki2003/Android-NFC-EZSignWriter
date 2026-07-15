package com.example.ez_sign_writer_app

import android.content.Intent
import android.net.Uri
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.TextView
import android.text.InputType
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.anarres.lzo.LzoLibrary
import org.anarres.lzo.lzo_uintp
import kotlin.math.pow
import androidx.core.graphics.get
import androidx.core.graphics.scale
import androidx.core.widget.doAfterTextChanged
import kotlin.math.abs

enum class ImageRotation(val degrees: Int, val displayName: String) {
    NONE(0, "0°（現在の向き）"),
    CLOCKWISE_90(90, "右90°"),
    ROTATE_180(180, "180°"),
    COUNTERCLOCKWISE_90(270, "左90°"),
}

class MainActivity : AppCompatActivity(), NfcAdapter.ReaderCallback {

    private lateinit var statusText: TextView
    private var nfcAdapter: NfcAdapter? = null
    private var selectedBitmap: Bitmap? = null
    private var selectedImageName: String? = null
    private var imageValidationError: String? = null
    private var sourceBitmap: Bitmap? = null
    @Volatile
    private var rawModeEnabled: Boolean = false
    @Volatile
    private var imageRotation: ImageRotation = ImageRotation.NONE
    @Volatile
    private var rawScale: Int = 1
    private var rawScaleInputValid: Boolean = true
    // 画像選択ピッカー
    private val pickMedia = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            loadSelectedImage(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // レイアウト生成
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        ViewCompat.setOnApplyWindowInsetsListener(layout) { view, insets ->
            val safeArea = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.setPadding(50 + safeArea.left, 50 + safeArea.top, 50 + safeArea.right, 50 + safeArea.bottom)
            insets
        }
        val btn = Button(this).apply {
            text = "画像を選択"
            setOnClickListener {
                pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }
        }
        val rotationLabel = TextView(this).apply {
            text = "画像の回転"
        }
        val rotationGrid = GridLayout(this).apply {
            columnCount = 2
        }
        val rotationButtons = ImageRotation.entries.map { rotation ->
            RadioButton(this).apply {
                text = rotation.displayName
                isChecked = rotation == ImageRotation.NONE
                setOnCheckedChangeListener { _, isChecked ->
                    if (isChecked) {
                        imageRotation = rotation
                        validateSelectedImage()
                    }
                }
            }
        }
        rotationButtons.forEach { button ->
            button.setOnClickListener {
                rotationButtons.forEach { it.isChecked = it === button }
            }
            rotationGrid.addView(button)
        }
        val rawModeCheckBox = CheckBox(this).apply {
            text = "rawモード"
        }
        val scaleLabel = TextView(this).apply {
            text = "raw倍率（整数倍・pixel perfect）"
        }
        val scaleControls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val actualSizeButton = Button(this).apply { text = "等倍" }
        val scaleDownButton = Button(this).apply { text = "−" }
        val scaleInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText("1")
            selectAll()
            contentDescription = "raw画像の倍率"
        }
        val scaleUpButton = Button(this).apply { text = "＋" }
        val maxScaleButton = Button(this).apply { text = "最大" }
        scaleControls.addView(actualSizeButton)
        scaleControls.addView(scaleDownButton)
        scaleControls.addView(scaleInput, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        scaleControls.addView(scaleUpButton)
        scaleControls.addView(maxScaleButton)

        fun setScale(value: Int) {
            scaleInput.setText(value.toString())
            scaleInput.selectAll()
        }
        fun updateScaleControls() {
            val enabled = rawModeEnabled
            scaleLabel.isEnabled = enabled
            actualSizeButton.isEnabled = enabled
            scaleDownButton.isEnabled = enabled && rawScaleInputValid && rawScale > 1
            scaleInput.isEnabled = enabled
            scaleUpButton.isEnabled = enabled && rawScaleInputValid && rawScale < Int.MAX_VALUE
            maxScaleButton.isEnabled = enabled
        }
        rawModeCheckBox.setOnCheckedChangeListener { _, isChecked ->
            rawModeEnabled = isChecked
            updateScaleControls()
            validateSelectedImage()
        }
        scaleInput.doAfterTextChanged { text ->
            val value = text?.toString()?.toIntOrNull()
            rawScaleInputValid = value != null && value >= 1
            if (rawScaleInputValid) rawScale = value!!
            updateScaleControls()
            validateSelectedImage()
        }
        actualSizeButton.setOnClickListener { setScale(1) }
        scaleDownButton.setOnClickListener { setScale(rawScale - 1) }
        scaleUpButton.setOnClickListener { setScale(rawScale + 1) }
        maxScaleButton.setOnClickListener {
            selectedBitmap?.let { bitmap ->
                ImageConverter.maxRawScale(bitmap, imageRotation).takeIf { it >= 1 }?.let(::setScale)
            }
        }
        statusText = TextView(this).apply {
            text = "Please select an image."
            textSize = 18f
            setPadding(0, 50, 0, 0)
        }

        layout.addView(btn)
        layout.addView(rotationLabel)
        layout.addView(rotationGrid)
        layout.addView(rawModeCheckBox)
        layout.addView(scaleLabel)
        layout.addView(scaleControls)
        layout.addView(statusText)
        setContentView(layout)

        updateScaleControls()

        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        handleSharedImage(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleSharedImage(intent)
    }

    override fun onResume() {
        super.onResume()
        // NFCリーダ有効化
        // ISO 14443-A(Type A)
        nfcAdapter?.enableReaderMode(this, this,
            NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK, null)
    }

    override fun onPause() {
        super.onPause()
        // NFCリーダ無効化
        nfcAdapter?.disableReaderMode(this)
    }

    // NFCタグ検知時の処理
    override fun onTagDiscovered(tag: Tag) {
        // 画像選択前にタグ検知した場合は処理しない
        val bitmap = sourceBitmap ?: run {
            updateStatus(imageValidationError ?: "Please select an image.")
            return
        }


        val isoDep = IsoDep.get(tag) ?: return
        try {
            // APDU通信開始
            isoDep.connect()
            isoDep.timeout = 100000
            updateStatus("Communicating...")

            // 認証
            val authRes = isoDep.transceive(hexToBytes("002000010420091210"))
            if (!hex(authRes).endsWith("9000")) throw Exception("Authentication Failed.")

            // 画像データのブロック変換と送信
            val rawBlocks = ImageConverter.convertToRawBlocks(bitmap, rawModeEnabled, imageRotation, rawScale)
            for (blockNo in 0 until 15) {
                updateStatus("Send: Block $blockNo / 14")

                // LZO1X-1による圧縮
                val compressed = LzoService.compressBlock(rawBlocks[blockNo])

                // データ送信
                val apdus = createF0D3Commands(blockNo, compressed)
                for (apdu in apdus) {
                    val res = isoDep.transceive(apdu)
                    if (!hex(res).endsWith("9000")) throw Exception("Block $blockNo: Send Failed.")
                }
            }

            // 書き込み
            updateStatus("Writing...")
            val refreshRes = isoDep.transceive(hexToBytes("F0D4858000"))
            if (!hex(refreshRes).endsWith("9000")) throw Exception("Write Failed.")

            // 書き込みが完了したかポーリング
            var isBusy = true
            while (isBusy) {
                Thread.sleep(1000)
                val pollRes = isoDep.transceive(hexToBytes("F0DE000001"))
                val resHex = hex(pollRes)
                if (resHex.startsWith("00")) {  // 更新完了
                    isBusy = false
                } else if (!resHex.startsWith("01")) {
                    throw Exception("Polling Error.")
                }
            }
            updateStatus("Writing complete!!")

        } catch (e: Exception) {
            updateStatus("Error: ${e.message}")
            Log.e("NFC", "Write Error", e)
        } finally {
            isoDep.close()
        }
    }

    // ユーティリティ関数群
    private fun updateStatus(msg: String) = runOnUiThread { statusText.text = msg }
    private fun hexToBytes(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02X".format(it) }

    private fun validateSelectedImage() {
        val bitmap = selectedBitmap ?: run {
            sourceBitmap = null
            imageValidationError = null
            updateStatus("Please select an image.")
            return
        }

        val imageName = selectedImageName ?: "selected image"
        if (rawModeEnabled && !rawScaleInputValid) {
            sourceBitmap = null
            imageValidationError = "Raw mode error: 倍率は1以上の整数で入力してください。"
            updateStatus(imageValidationError!!)
            return
        }
        if (rawModeEnabled) {
            val error = ImageConverter.rawSizeError(bitmap, imageRotation, rawScale)
            if (error != null) {
                sourceBitmap = null
                imageValidationError = error
                updateStatus(error)
                return
            }
        }

        sourceBitmap = bitmap
        imageValidationError = null
        val transformation = if (rawModeEnabled) {
            "${imageRotation.displayName}・${rawScale}x・中央配置"
        } else {
            imageRotation.displayName
        }
        updateStatus("Image Ready: $imageName\n変換: $transformation\nPlease hold your device over the NFC e-paper display.")
    }

    private fun handleSharedImage(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return

        val uri = intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            ?: intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
        if (uri == null) {
            clearSelectedImage("共有された画像を読み込めませんでした。")
            return
        }

        loadSelectedImage(uri)
    }

    private fun loadSelectedImage(uri: Uri) {
        try {
            val bitmap = contentResolver.openInputStream(uri)?.use(BitmapFactory::decodeStream)
            if (bitmap == null) {
                clearSelectedImage("画像を読み込めませんでした。")
                return
            }

            selectedBitmap = bitmap
            selectedImageName = getDisplayName(uri)
            validateSelectedImage()
        } catch (e: Exception) {
            Log.e("Image", "Failed to load image", e)
            clearSelectedImage("画像を読み込めませんでした。")
        }
    }

    private fun clearSelectedImage(message: String) {
        selectedBitmap = null
        selectedImageName = null
        sourceBitmap = null
        imageValidationError = message
        updateStatus(message)
    }

    private fun getDisplayName(uri: Uri): String {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIndex >= 0 && cursor.moveToFirst()) {
                return cursor.getString(nameIndex)
            }
        }

        return uri.lastPathSegment ?: uri.toString()
    }

    // NFC送信用コマンドパケット作成
    private fun createF0D3Commands(blockNo: Int, data: ByteArray): List<ByteArray> {
        val list = mutableListOf<ByteArray>()
        var offset = 0
        var fragNo = 0
        //　250byteごとに分割 (通信データ量制約のため)
        while (offset < data.size) {
            val size = minOf(250, data.size - offset)
            val isLast = (offset + size >= data.size)
            val header = byteArrayOf(0xF0.toByte(), 0xD3.toByte(), 0x00.toByte(), if (isLast) 1 else 0, (size + 2).toByte())
            val prefix = byteArrayOf(blockNo.toByte(), fragNo.toByte())
            list.add(header + prefix + data.copyOfRange(offset, offset + size))
            offset += size
            fragNo++
        }
        return list
    }
}

object ImageConverter {
    const val WIDTH = 400
    const val HEIGHT = 300
    private const val COLOR_THRESHOLD = 128

    // 0:黒, 1:白, 2:黄, 3:赤
    private val PALETTE_R = floatArrayOf(0.0f, 1.0f, 1.0f, 1.0f)
    private val PALETTE_G = floatArrayOf(0.0f, 1.0f, 1.0f, 0.0f)
    private val PALETTE_B = floatArrayOf(0.0f, 1.0f, 0.0f, 0.0f)

    // 画像データのブロック変換
    fun convertToRawBlocks(
        source: Bitmap,
        rawMode: Boolean,
        rotation: ImageRotation = ImageRotation.NONE,
        rawScale: Int = 1,
    ): List<ByteArray> {
        val orientedSource = rotate(source, rotation.degrees)

        if (rawMode) {
            return convertRawImageToBlocks(prepareRawImage(orientedSource, rawScale))
        }

        val pixelCount = WIDTH * HEIGHT

        // リサイズ
        val scaledBitmap = resizeToFill(orientedSource, WIDTH, HEIGHT)

        // 正規化とRGBチャンネル分解
        val bufR = FloatArray(pixelCount)
        val bufG = FloatArray(pixelCount)
        val bufB = FloatArray(pixelCount)
        for (y in 0 until HEIGHT) {
            for (x in 0 until WIDTH) {
                val px = scaledBitmap[x, y]
                val idx = y * WIDTH + x
                bufR[idx] = Color.red(px) / 255.0f
                bufG[idx] = Color.green(px) / 255.0f
                bufB[idx] = Color.blue(px) / 255.0f
            }
        }

        // ガンマ補正
        val gamma = 0.9f
        for (i in 0 until pixelCount) {
            bufR[i] = bufR[i].toDouble().pow(gamma.toDouble()).toFloat()
            bufG[i] = bufG[i].toDouble().pow(gamma.toDouble()).toFloat()
            bufB[i] = bufB[i].toDouble().pow(gamma.toDouble()).toFloat()
        }

        // ディザリング(Atkinson Dithering)
        val indexMap = IntArray(pixelCount)
        val saturationWeights = floatArrayOf(0.0f, 0.0f, 1.1f, 0.5f) // 各色の優先度
        for (y in 0 until HEIGHT) {
            val rowBase = y * WIDTH
            for (x in 0 until WIDTH) {
                val index = rowBase + x

                val cr = bufR[index]
                val cg = bufG[index]
                val cb = bufB[index]

                // 近似色の計算と決定
                var bestIndex = 0
                var bestDistance = Float.MAX_VALUE
                for (i in 0 until 4) {
                    val dr = cr - PALETTE_R[i]
                    val dg = cg - PALETTE_G[i]
                    val db = cb - PALETTE_B[i]

                    var distance = dr * dr + dg * dg + db * db  // ユークリッド距離
                    // 飽和重みによる距離調整
                    // 飽和重みが大きければ距離が小さくなる -> その色が選択されやすくなる
                    distance *= (1.0f / (1.0f + saturationWeights[i] * 0.5f))

                    // 最適な色インデックスを更新
                    if (distance < bestDistance) {
                        bestDistance = distance
                        bestIndex = i
                    }
                }
                indexMap[index] = bestIndex


                // 誤差拡散の強度 数値が小さいほどディザリングが適用されない(ベタ塗り)
                // 通常 0.075~0.125
                val factorR = 0.075f
                val factorG = 0.075f
                val factorB = 0.075f
                val er = (cr - PALETTE_R[bestIndex]) * factorR
                val eg = (cg - PALETTE_G[bestIndex]) * factorG
                val eb = (cb - PALETTE_B[bestIndex]) * factorB

                // 誤差拡散の計算
                val thresholds = 0.02f  // 誤差許容閾値
                if (abs(er) > thresholds || abs(eg) > thresholds || abs(eb) > thresholds) {
                    diffuse(x + 1, y, er, eg, eb, bufR, bufG, bufB, WIDTH, HEIGHT)
                    diffuse(x + 2, y, er, eg, eb, bufR, bufG, bufB, WIDTH, HEIGHT)
                    diffuse(x - 1, y + 1, er, eg, eb, bufR, bufG, bufB, WIDTH, HEIGHT)
                    diffuse(x, y + 1, er, eg, eb, bufR, bufG, bufB, WIDTH, HEIGHT)
                    diffuse(x + 1, y + 1, er, eg, eb, bufR, bufG, bufB, WIDTH, HEIGHT)
                    diffuse(x, y + 2, er, eg, eb, bufR, bufG, bufB, WIDTH, HEIGHT)
                }
            }
        }

        // データパッキングとブロック分割
        return packToIndexBlocks(indexMap, WIDTH, HEIGHT)
    }

    fun maxRawScale(source: Bitmap, rotation: ImageRotation): Int {
        val (width, height) = rotatedDimensions(source, rotation)
        return minOf(WIDTH / width, HEIGHT / height)
    }

    fun rawSizeError(source: Bitmap, rotation: ImageRotation, scale: Int): String? {
        require(scale >= 1) { "Scale must be at least 1." }
        val (width, height) = rotatedDimensions(source, rotation)
        val scaledWidth = width.toLong() * scale
        val scaledHeight = height.toLong() * scale
        if (scaledWidth <= WIDTH && scaledHeight <= HEIGHT) return null

        return "Raw mode error: ${rotation.displayName}・${scale}xでは${scaledWidth}x${scaledHeight}pxです。${WIDTH}x${HEIGHT}px以内に収めてください。"
    }

    private fun rotatedDimensions(source: Bitmap, rotation: ImageRotation): Pair<Int, Int> =
        if (rotation == ImageRotation.CLOCKWISE_90 || rotation == ImageRotation.COUNTERCLOCKWISE_90) {
            source.height to source.width
        } else {
            source.width to source.height
        }

    private fun prepareRawImage(source: Bitmap, scale: Int): Bitmap {
        rawSizeError(source, ImageRotation.NONE, scale)?.let { throw IllegalArgumentException(it) }
        val scaledWidth = source.width * scale
        val scaledHeight = source.height * scale
        val scaled = if (scale == 1) source else source.scale(scaledWidth, scaledHeight, false)
        val result = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(result)
        canvas.drawColor(Color.WHITE)
        canvas.drawBitmap(scaled, ((WIDTH - scaledWidth) / 2).toFloat(), ((HEIGHT - scaledHeight) / 2).toFloat(), null)
        return result
    }

    private fun convertRawImageToBlocks(source: Bitmap): List<ByteArray> {
        if (source.width != WIDTH || source.height != HEIGHT) {
            throw IllegalArgumentException("Raw mode requires ${WIDTH}x${HEIGHT}px image. Selected: ${source.width}x${source.height}px.")
        }

        val indexMap = IntArray(WIDTH * HEIGHT)
        for (y in 0 until HEIGHT) {
            for (x in 0 until WIDTH) {
                indexMap[y * WIDTH + x] = classifyFixedThreshold(source[x, y])
            }
        }

        return packToIndexBlocks(indexMap, WIDTH, HEIGHT)
    }

    private fun classifyFixedThreshold(pixel: Int): Int {
        val r = Color.red(pixel) >= COLOR_THRESHOLD
        val g = Color.green(pixel) >= COLOR_THRESHOLD
        val b = Color.blue(pixel) >= COLOR_THRESHOLD

        return when {
            r && g && b -> 1 // 白
            r && g -> 2 // 黄
            r -> 3 // 赤
            else -> 0 // 黒
        }
    }

    private fun rotate(source: Bitmap, degrees: Int): Bitmap {
        if (degrees % 360 == 0) return source

        val matrix = Matrix().apply { postRotate((degrees % 360).toFloat()) }
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, false)
    }

    // リサイズ
    private fun resizeToFill(source: Bitmap, targetW: Int, targetH: Int): Bitmap {
        // アスペクト比を維持したまま、描画領域に収まるようにリサイズ
        val scale = minOf(targetW.toFloat() / source.width, targetH.toFloat() / source.height)
        val sw = (scale * source.width).toInt()
        val sh = (scale * source.height).toInt()
        val scaled = source.scale(sw, sh)
        val result = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(result)
        canvas.drawColor(android.graphics.Color.WHITE)
        canvas.drawBitmap(scaled, (targetW - sw) / 2f, (targetH - sh) / 2f, null)
        return result
    }

    // 誤差拡散処理
    private fun diffuse(x: Int, y: Int, er: Float, eg: Float, eb: Float,
                        rBuf: FloatArray, gBuf: FloatArray, bBuf: FloatArray, w: Int, h: Int) {
        if (x in 0 until w && y in 0 until h) {
            val i = y * w + x
            rBuf[i] += er
            gBuf[i] += eg
            bBuf[i] += eb
        }
    }

    // データパッキングとブロック分割(15ブロックx2000バイト)
    private fun packToIndexBlocks(indexMap: IntArray, w: Int, h: Int): List<ByteArray> {
        // 画像全体: width 400px x height 300px = 120,000px
        // データサイズ: 120,000px / 4px/byte = 30,000byte
        val blocks = mutableListOf<ByteArray>()
        // ブロック数: 30,000byte / 2,000byte/block = 15
        for (b in 0 until 15) {
            val data = ByteArray(2000)
            // 1ブロックあたりの行数: 2,000byte/block / 100byte/行 = 20
            for (lineY in 0 until 20) {
                val y = b * 20 + lineY
                // 1行あたりデータサイズ: width 400px / 4px/byte = 100byte
                for (xb in 0 until 100) {
                    // 1バイトのパッキング
                    // 4色=2bitなので8bitで4ピクセル分パッキングできる
                    var packed = 0
                    for (p in 0 until 4) {
                        val x = 399 - (xb * 4 + (3 - p))    // 右から左に読む
                        val colorIdx = indexMap[y * w + x]
                        packed = packed or (colorIdx shl (p * 2))
                    }
                    data[lineY * 100 + xb] = packed.toByte()
                }
            }
            blocks.add(data)
        }
        return blocks
    }
}

object LzoService {
    private val library = LzoLibrary.getInstance()
    private val compressor = library.newCompressor(null, null)

    // LZO1X-1圧縮
    fun compressBlock(raw: ByteArray): ByteArray {
        val out = ByteArray(raw.size + (raw.size / 16) + 64)
        val len = lzo_uintp()
        compressor.compress(raw, 0, raw.size, out, 0, len)
        return out.copyOfRange(0, len.value)
    }
}
