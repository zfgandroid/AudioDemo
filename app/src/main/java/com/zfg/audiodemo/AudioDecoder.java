package com.zfg.audiodemo;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.os.Environment;
import android.util.Log;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

import static com.zfg.audiodemo.AudioEncoder.ENCODER_FILE;
import static com.zfg.audiodemo.MainActivity.FOLDER_NAME;
import static com.zfg.audiodemo.MainActivity.TAG;

/**
 * 音频硬解码
 */
public class AudioDecoder {

    //用于分离出音频轨道
    private MediaExtractor mMediaExtractor;
    private MediaCodec mMediaCodec;
    private MediaCodec.BufferInfo mBufferInfo;
    private File mTargetFile;
    private File mPCMFile;
    private FileOutputStream mFileOutputStream;
    private IDecodeDelegate mIDecodeDelegate;

    /**
     * AAC格式
     */
    private String MINE_TYPE_AAC = "audio/mp4a-latm";

    /**
     * 解码后的pcm文件
     */
    public static final String DECODER_FILE = "decoder_file";

    /**
     * 初始化解码器
     */
    public void initDecoder(IDecodeDelegate iDecodeDelegate) {

        mIDecodeDelegate = iDecodeDelegate;

        File fileFolder = new File(Environment.getExternalStorageDirectory().getAbsolutePath() + "/" + FOLDER_NAME);
        String fileFolderPath = fileFolder.getAbsolutePath();
        mTargetFile = new File(fileFolderPath + "/" + ENCODER_FILE + ".aac");
        if (!mTargetFile.exists()) {
            Log.e(TAG, "The source file does not exist!");
            return;
        }
        mPCMFile = new File(fileFolderPath + "/" + DECODER_FILE + ".pcm");
        if (!mPCMFile.exists()) {
            try {
                mPCMFile.createNewFile();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        try {
            mFileOutputStream = new FileOutputStream(mPCMFile.getAbsoluteFile());
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        }
        mMediaExtractor = new MediaExtractor();
        try {
            //设置资源
            mMediaExtractor.setDataSource(mTargetFile.getAbsolutePath());
            //获取含有音频的MediaFormat
            MediaFormat mediaFormat = createMediaFormat();
            mMediaCodec = MediaCodec.createDecoderByType(MINE_TYPE_AAC);
            //解码时最后一个参数设置为0
            mMediaCodec.configure(mediaFormat, null, null, 0);
            if (mMediaCodec == null) {
                Log.e(TAG, "Create media decode failed");
                return;
            }
            //进入Runnable状态
            mMediaCodec.start();
            mBufferInfo = new MediaCodec.BufferInfo();
            Log.i(TAG, "Create media decode succeed");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private MediaFormat createMediaFormat() {
        //获取文件的轨道数，做循环得到含有音频的mediaFormat
        for (int i = 0; i < mMediaExtractor.getTrackCount(); i++) {
            MediaFormat mediaFormat = mMediaExtractor.getTrackFormat(i);
            //MediaFormat键值对应
            String mime = mediaFormat.getString(MediaFormat.KEY_MIME);
            if (mime.contains("audio/")) {
                mMediaExtractor.selectTrack(i);
                return mediaFormat;
            }
        }
        return null;
    }

    /**
     * 开始解码
     */
    public void decode() {

        boolean inputSawEos = false;
        boolean outputSawEos = false;
        long timeout = 5000;

        while (!outputSawEos) {
            if (!inputSawEos) {
                //每5000毫秒查询一次
                int inputIndex = mMediaCodec.dequeueInputBuffer(timeout);
                //输入缓存index可用
                if (inputIndex >= 0) {
                    //获取可用的输入缓存
                    ByteBuffer inputBuffer = mMediaCodec.getInputBuffer(inputIndex);
                    //从MediaExtractor读取数据到输入缓存中，返回读取长度
                    int bufferSize = mMediaExtractor.readSampleData(inputBuffer, 0);
                    if (bufferSize <= 0) {//已经读取完
                        //标志输入完毕
                        inputSawEos = true;
                        //做标识
                        mMediaCodec.queueInputBuffer(inputIndex, 0, 0, timeout, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                    } else {
                        long time = mMediaExtractor.getSampleTime();
                        //将输入缓存放入MediaCodec中
                        mMediaCodec.queueInputBuffer(inputIndex, 0, bufferSize, time, 0);
                        //指向下一帧
                        mMediaExtractor.advance();
                    }
                }
            }
            //获取输出缓存，需要传入MediaCodec.BufferInfo 用于存储ByteBuffer信息
            int outputIndex = mMediaCodec.dequeueOutputBuffer(mBufferInfo, timeout);
            if (outputIndex >= 0) {
                if ((mBufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                    mMediaCodec.releaseOutputBuffer(outputIndex, false);
                    continue;
                }
                //有输出数据
                if (mBufferInfo.size > 0) {
                    //获取输出缓存
                    ByteBuffer outputBuffer = mMediaCodec.getOutputBuffer(outputIndex);
                    //设置ByteBuffer的position位置
                    outputBuffer.position(mBufferInfo.offset);
                    //设置ByteBuffer访问的结点
                    outputBuffer.limit(mBufferInfo.offset + mBufferInfo.size);
                    byte[] targetData = new byte[mBufferInfo.size];
                    //将数据填充到数组中
                    outputBuffer.get(targetData);
                    try {
                        mFileOutputStream.write(targetData);
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
                //释放输出缓存
                mMediaCodec.releaseOutputBuffer(outputIndex, false);
                //判断缓存是否完结
                if ((mBufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    outputSawEos = true;
                }
            }
        }
        //释放资源
        try {
            mFileOutputStream.flush();
            mFileOutputStream.close();
            mMediaCodec.stop();
            mMediaCodec.release();
            mMediaExtractor.release();
            if (null != mIDecodeDelegate) {
                mIDecodeDelegate.decodeResult(true);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * 是否解码完成回调
     */
    interface IDecodeDelegate {
        void decodeResult(boolean isFinish);
    }
}
