package com.instrumentalizer

import ai.onnxruntime.*
import android.content.Context
import android.media.*
import android.net.Uri
import java.io.File
import java.nio.*

/** Decodes any audio/video file, removes vocals with an ONNX stem-separation model, writes a stereo WAV. */
object Separator {
    const val SR = 44100
    const val SEG = SR * 10   // model window (10 s)
    const val VOCAL = 3       // stem order: drums, bass, other, vocals

    fun run(ctx: Context, uri: Uri, overlap: Float, onP: (Float) -> Unit): File {
        val pcm = decode(ctx, uri)
        val n = pcm[0].size
        val env = OrtEnvironment.getEnvironment()
        val ses = env.createSession(ctx.assets.open("model.onnx").readBytes(),
            OrtSession.SessionOptions().apply { setIntraOpNumThreads(4) })
        val out = Array(2) { FloatArray(n) }
        val wsum = FloatArray(n)
        val hop = (SEG * (1 - overlap)).toInt()
        val win = FloatArray(SEG) { minOf(it + 1, SEG - it).toFloat() }  // triangular cross-fade
        var s = 0
        while (true) {
            val len = minOf(SEG, n - s)
            val buf = FloatArray(2 * SEG)
            for (c in 0..1) System.arraycopy(pcm[c], s, buf, c * SEG, len)
            val t = OnnxTensor.createTensor(env, FloatBuffer.wrap(buf), longArrayOf(1, 2, SEG.toLong()))
            ses.run(mapOf(ses.inputNames.first() to t)).use { r ->
                @Suppress("UNCHECKED_CAST")
                val y = (r[0].value as Array<Array<Array<FloatArray>>>)[0]   // [stem][ch][samples]
                for (st in y.indices) if (st != VOCAL)
                    for (c in 0..1) for (i in 0 until len) out[c][s + i] += y[st][c][i] * win[i]
                for (i in 0 until len) wsum[s + i] += win[i]
            }
            t.close()
            onP(minOf(1f, (s + len).toFloat() / n))
            if (s + len >= n) break
            s += hop
        }
        ses.close()
        for (c in 0..1) for (i in 0 until n) if (wsum[i] > 0) out[c][i] /= wsum[i]
        val f = File(ctx.cacheDir, "instrumental.wav")
        writeWav(f, out)
        return f
    }

    private fun decode(ctx: Context, uri: Uri): Array<FloatArray> {
        val ex = MediaExtractor().apply { setDataSource(ctx, uri, null) }
        val ti = (0 until ex.trackCount).first { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)!!.startsWith("audio") }
        ex.selectTrack(ti)
        val fmt = ex.getTrackFormat(ti)
        val codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(fmt, null, null, 0); codec.start()
        var ch = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var sr = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var data = ShortArray(1 shl 22); var len = 0
        val info = MediaCodec.BufferInfo(); var inDone = false; var outDone = false
        while (!outDone) {
            if (!inDone) {
                val i = codec.dequeueInputBuffer(10000)
                if (i >= 0) {
                    val sz = ex.readSampleData(codec.getInputBuffer(i)!!, 0)
                    if (sz < 0) { codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true }
                    else { codec.queueInputBuffer(i, 0, sz, ex.sampleTime, 0); ex.advance() }
                }
            }
            val o = codec.dequeueOutputBuffer(info, 10000)
            if (o >= 0) {
                val sb = codec.getOutputBuffer(o)!!.order(ByteOrder.nativeOrder()).asShortBuffer()
                val k = info.size / 2
                if (len + k > data.size) data = data.copyOf(maxOf(data.size * 2, len + k))
                sb.get(data, len, k); len += k
                codec.releaseOutputBuffer(o, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outDone = true
            } else if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                ch = codec.outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                sr = codec.outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            }
        }
        codec.stop(); codec.release(); ex.release()
        val frames = len / ch
        val raw = Array(2) { c -> FloatArray(frames) { data[it * ch + if (ch > 1) c else 0] / 32768f } }
        if (sr == SR) return raw
        val m = (frames.toLong() * SR / sr).toInt()   // linear resample to 44.1 kHz
        return Array(2) { c -> FloatArray(m) { i ->
            val p = i.toDouble() * sr / SR; val a = p.toInt(); val fr = (p - a).toFloat()
            raw[c][a] * (1 - fr) + raw[c][minOf(a + 1, frames - 1)] * fr } }
    }

    private fun writeWav(f: File, d: Array<FloatArray>) {
        val n = d[0].size
        val b = ByteBuffer.allocate(44 + n * 4).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt(36 + n * 4).put("WAVEfmt ".toByteArray()).putInt(16)
            .putShort(1).putShort(2).putInt(SR).putInt(SR * 4).putShort(4).putShort(16)
            .put("data".toByteArray()).putInt(n * 4)
        for (i in 0 until n) for (c in 0..1)
            b.putShort((d[c][i].coerceIn(-1f, 1f) * 32767f).toInt().toShort())
        f.writeBytes(b.array())
    }
}
