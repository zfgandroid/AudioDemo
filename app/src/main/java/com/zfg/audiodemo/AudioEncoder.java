package com.zfg.audiodemo;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.Environment;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

import static com.zfg.audiodemo.MainActivity.FOLDER_NAME;
import static com.zfg.audiodemo.MainActivity.TAG;

/**
 * 音频编码器
 * 使用MediaCodec将PCM编码成AAC
 */
public class AudioEncoder {

    private MediaCodec mMediaCodec;
    private MediaCodec.BufferInfo mBufferInfo;
    private FileOutputStream mFileOutputStream;

    /**
     * AAC格式
     */
    private final String MINE_TYPE_AAC = MediaFormat.MIMETYPE_AUDIO_AAC;

    /**
     * 比特率（码率）
     */
    private int BIT_RATE = 128 * 1024;

    /**
     * 采样率
     */
    private static final int SAMPLE_RATE_HZ = 44100;

    /**
     * 声道数
     */
    private static final int CHANNEL_COUNT = 1;

    /**
     * 缓存大小
     */
    private static final int MAX_BUFFER_SIZE = 10 * 1024;

    /**
     * 编码后的aac文件
     */
    public static final String ENCODER_FILE = "encoder_file";

    /**
     * 初始化编码器
     */
    public void initEncoder() {
        try {
            //创建文件夹
            File fileFolder = new File(Environment.getExternalStorageDirectory().getAbsolutePath() + "/" + FOLDER_NAME);
            if (!fileFolder.exists()) {
                fileFolder.mkdir();
            }
            String fileFolderPath = fileFolder.getAbsolutePath();
            final File file = new File(fileFolderPath + "/" + ENCODER_FILE + ".aac");
            if (!file.exists()) {
                try {
                    file.createNewFile();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
            mFileOutputStream = new FileOutputStream(file.getAbsoluteFile());

            //设置编码参数
            MediaFormat mediaFormat = MediaFormat.createAudioFormat(MINE_TYPE_AAC, SAMPLE_RATE_HZ, CHANNEL_COUNT);
            mediaFormat.setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE);
            mediaFormat.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
            mediaFormat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_BUFFER_SIZE);
            //根据类型实例化一个编码器
            mMediaCodec = MediaCodec.createEncoderByType(MINE_TYPE_AAC);
            //MediaCodec.CONFIGURE_FLAG_ENCODE 表示需要配置一个编码器，而不是解码器
            mMediaCodec.configure(mediaFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            if (mMediaCodec == null) {
                Log.e(TAG, "Create media encode failed");
                return;
            }
            //start（）后进入执行状态，才能做后续的操作
            mMediaCodec.start();
            //解码后的数据，包含每一个buffer的元数据信息
            mBufferInfo = new MediaCodec.BufferInfo();
            Log.i(TAG, "Create media encode succeed");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * 编码
     *
     * @param data
     */
    public void encodeData(byte[] data) {
        //dequeueInputBuffer（time）需要传入一个时间值，-1表示一直等待，0表示不等待有可能会丢帧，其他表示等待多少毫秒
        //获取输入缓存的index
        int inputIndex = mMediaCodec.dequeueInputBuffer(-1);
        if (inputIndex >= 0) {
            ByteBuffer inputByteBuffer = mMediaCodec.getInputBuffer(inputIndex);
            inputByteBuffer.clear();
            //添加数据
            inputByteBuffer.put(data);
            //限制ByteBuffer的访问长度
            inputByteBuffer.limit(data.length);
            //把输入缓存塞回去给MediaCodec
            mMediaCodec.queueInputBuffer(inputIndex, 0, data.length, 0, 0);
        }

        //获取输出缓存的index
        int outputIndex = mMediaCodec.dequeueOutputBuffer(mBufferInfo, 0);
        while (outputIndex >= 0) {
            //获取缓存信息的长度
            int byteBufSize = mBufferInfo.size;
            //添加ADTS头部后的长度
            int bytePacketSize = byteBufSize + 7;

            ByteBuffer outByteBuffer = mMediaCodec.getOutputBuffer(outputIndex);
            outByteBuffer.position(mBufferInfo.offset);
            outByteBuffer.limit(mBufferInfo.offset + mBufferInfo.size);

            byte[] targetByte = new byte[bytePacketSize];
            //添加ADTS头部
            addADTStoPacket(targetByte, bytePacketSize);
            //将编码得到的AAC数据 取出到byte[]中 偏移量offset=7
            outByteBuffer.get(targetByte, 7, byteBufSize);

            outByteBuffer.position(mBufferInfo.offset);

            try {
                mFileOutputStream.write(targetByte);
            } catch (IOException e) {
                e.printStackTrace();
            }
            //释放
            mMediaCodec.releaseOutputBuffer(outputIndex, false);
            outputIndex = mMediaCodec.dequeueOutputBuffer(mBufferInfo, 0);
        }
    }

    /**
     * 给编码出的aac裸流添加adts头字段
     *
     * @param packet    要空出前7个字节，否则会搞乱数据
     * @param packetLen
     */
    private void addADTStoPacket(byte[] packet, int packetLen) {
        int profile = 2;  //AAC LC
        int freqIdx = 4;  //44.1KHz
        int chanCfg = 2;  //CPE
        packet[0] = (byte) 0xFF;
        packet[1] = (byte) 0xF9;
        packet[2] = (byte) (((profile - 1) << 6) + (freqIdx << 2) + (chanCfg >> 2));
        packet[3] = (byte) (((chanCfg & 3) << 6) + (packetLen >> 11));
        packet[4] = (byte) ((packetLen & 0x7FF) >> 3);
        packet[5] = (byte) (((packetLen & 7) << 5) + 0x1F);
        packet[6] = (byte) 0xFC;
    }

    /**
     * 停止编码
     */
    public void stopEncode() {
        if (mMediaCodec != null) {
            mMediaCodec.stop();
            mMediaCodec.release();
            Log.i(TAG, "Stop encode");
        }
    }
}
