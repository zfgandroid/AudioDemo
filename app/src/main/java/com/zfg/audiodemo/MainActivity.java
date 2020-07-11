package com.zfg.audiodemo;

import android.Manifest;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Environment;
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static com.zfg.audiodemo.AudioDecoder.DECODER_FILE;

/**
 * @author zfg
 */
public class MainActivity extends AppCompatActivity implements View.OnClickListener
        , AudioDecoder.IDecodeDelegate {

    public static final String TAG = "Audio";

    private static final int PERMISSIONS_REQUEST = 100;

    /**
     * 需要申请的运行时权限
     */
    private String[] permissions = new String[]{
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
    };

    /**
     * 被用户拒绝的权限列表
     */
    private List<String> mPermissionList = new ArrayList<>();

    /**
     * 采样率，现在能够保证在所有设备上使用的采样率是44100Hz, 但是其他的采样率（22050, 16000, 11025）在一些设备上也可以使用。
     */
    private static final int SAMPLE_RATE_HZ = 44100;

    /**
     * 录音声道数，CHANNEL_IN_MONO and CHANNEL_IN_STEREO. 其中CHANNEL_IN_MONO是可以保证在所有设备能够使用的。
     */
    private static final int CHANNEL_IN_CONFIG = AudioFormat.CHANNEL_IN_MONO;

    /**
     * 播放声道数，CHANNEL_OUT_MONO and CHANNEL_OUT_STEREO
     */
    private static final int CHANNEL_OUT_CONFIG = AudioFormat.CHANNEL_OUT_MONO;

    /**
     * 返回的音频数据的格式，ENCODING_PCM_8BIT, ENCODING_PCM_16BIT, and ENCODING_PCM_FLOAT.
     */
    private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;

    /**
     * 保存的文件
     */
    public static final String FOLDER_NAME = "AudioDemo";
    private static final String RECODE_FILE = "recode_file";

    private Button btn_record;
    private Button btn_play;
    private Button btn_decoder;

    private boolean isRecording;
    private AudioRecord audioRecord;
    private AudioTrack audioTrack;

    private ExecutorService executorService = null;
    private static final int CORE_POOL_SIZE = 2;
    private static final int MAXIMUM_POOL_SIZE = 2;
    private static final int KEEP_ALIVE_TIME = 60;
    //编码器
    private AudioEncoder audioEncoder;
    //默认播放解码前的数据，否则播放解码后的数据
    private boolean playBeforeDecoder = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_main);

        btn_record = findViewById(R.id.btn_record);
        btn_play = findViewById(R.id.btn_play);
        btn_decoder = findViewById(R.id.btn_decoder);

        btn_record.setOnClickListener(this);
        btn_play.setOnClickListener(this);
        btn_decoder.setOnClickListener(this);

        checkPermissions();

        if (null == executorService) {
            executorService = new ThreadPoolExecutor(
                    CORE_POOL_SIZE,
                    MAXIMUM_POOL_SIZE,
                    KEEP_ALIVE_TIME,
                    TimeUnit.SECONDS,
                    new LinkedBlockingQueue<Runnable>(),
                    new WorkThread(),
                    new ThreadPoolExecutor.AbortPolicy());
        }

    }

    private class WorkThread implements ThreadFactory {
        @Override
        public Thread newThread(Runnable r) {
            Thread thread = new Thread(r);
            thread.setName("WorkThread");
            return thread;
        }
    }

    private void checkPermissions() {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            for (int i = 0; i < permissions.length; i++) {
                if (ContextCompat.checkSelfPermission(this, permissions[i]) !=
                        PackageManager.PERMISSION_GRANTED) {
                    mPermissionList.add(permissions[i]);
                }
            }
            if (!mPermissionList.isEmpty()) {
                String[] permissions = mPermissionList.toArray(new String[mPermissionList.size()]);
                ActivityCompat.requestPermissions(this, permissions, PERMISSIONS_REQUEST);
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        if (requestCode == PERMISSIONS_REQUEST) {
            for (int i = 0; i < grantResults.length; i++) {
                if (grantResults[i] != PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(MainActivity.this, permissions[i] + " 权限被禁止了", Toast.LENGTH_SHORT).show();
                }
            }
        }
    }

    @Override
    public void onClick(View v) {
        switch (v.getId()) {
            case R.id.btn_record:
                if (btn_record.getText().toString().equals(getString(R.string.start_record))) {
                    btn_record.setText(getString(R.string.stop_record));
                    if (null != executorService) {
                        executorService.execute(recordRunnable);
                    }
                } else {
                    btn_record.setText(getString(R.string.start_record));
                    stopRecord();
                }
                break;
            case R.id.btn_play:
                if (btn_play.getText().toString().equals(getString(R.string.start_play))) {
                    btn_play.setText(getString(R.string.stop_play));
                    if (null != executorService) {
                        executorService.execute(playRunnable);
                    }
                }
                break;
            case R.id.btn_decoder:
                if (null != executorService) {
                    executorService.execute(decoderRunnable);
                }
                break;
            default:
                break;
        }
    }

    private Runnable recordRunnable = new Runnable() {

        @Override
        public void run() {

            startRecord();
        }
    };

    private Runnable playRunnable = new Runnable() {

        @Override
        public void run() {

            startPlay();
        }
    };

    private Runnable decoderRunnable = new Runnable() {
        @Override
        public void run() {

            AudioDecoder audioDecoder = new AudioDecoder();
            audioDecoder.initDecoder(MainActivity.this);
            audioDecoder.decode();
        }
    };

    @Override
    public void decodeResult(final boolean isFinish) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (isFinish) {
                    Toast.makeText(MainActivity.this, getResources().getString(R.string.decoder_finish), Toast.LENGTH_SHORT).show();
                }
            }
        });
    }

    /**
     * 开始录音
     */
    private void startRecord() {

        //创建AudioRecord对象所需的最小缓冲区大小
        final int minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE_HZ, CHANNEL_IN_CONFIG, AUDIO_FORMAT);
        //创建AudioRecord对象
        audioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE_HZ, CHANNEL_IN_CONFIG
                , AUDIO_FORMAT, minBufferSize);
        //初始化一个buffer
        final byte[] data = new byte[minBufferSize];
        //开始录音
        isRecording = true;
        audioRecord.startRecording();

        //创建文件夹
        File fileFolder = new File(Environment.getExternalStorageDirectory().getAbsolutePath() + "/" + FOLDER_NAME);
        if (!fileFolder.exists()) {
            fileFolder.mkdir();
        }

        String fileFolderPath = fileFolder.getAbsolutePath();
        final File file = new File(fileFolderPath + "/" + RECODE_FILE + ".pcm");
        if (!file.exists()) {
            try {
                file.createNewFile();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        FileOutputStream os = null;
        try {
            os = new FileOutputStream(file);
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        }

        //初始化编码器
        audioEncoder = new AudioEncoder();
        audioEncoder.initEncoder();

        if (null != os) {
            while (isRecording) {
                int read = audioRecord.read(data, 0, minBufferSize);
                // 如果读取音频数据没有出现错误，就将数据写入到文件
                if (AudioRecord.ERROR_INVALID_OPERATION != read) {
                    try {
                        os.write(data);
                        //将PCM编码成AAC
                        audioEncoder.encodeData(data);
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }
            try {
                os.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

    }

    /**
     * 停止录音
     */
    private void stopRecord() {
        isRecording = false;
        if (null != audioRecord) {
            audioRecord.stop();
            audioRecord.release();
            audioRecord = null;
        }

        if (null != audioEncoder) {
            audioEncoder.stopEncode();
        }
    }

    /**
     * 开始播放
     */
    private void startPlay() {

        //PCM文件路径
        File fileFolder = new File(Environment.getExternalStorageDirectory().getAbsolutePath() + "/" + "AudioDemo");
        String fileFolderPath = fileFolder.getAbsolutePath();

        String path;
        if (playBeforeDecoder) {
            //播放解码前的PCM数据
            path = fileFolderPath + "/" + RECODE_FILE + ".pcm";
        } else {
            //播放解码后的PCM数据
            path = fileFolderPath + "/" + DECODER_FILE + ".pcm";
        }

        File file = new File(path);
        if (!file.exists()) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    btn_play.setText(getString(R.string.start_play));
                    Toast.makeText(MainActivity.this, getString(R.string.file_not_exist), Toast.LENGTH_SHORT).show();
                }
            });
            return;
        }

        //创建AudioTrack对象所需的估计最小缓冲区大小
        final int minBufferSize = AudioTrack.getMinBufferSize(SAMPLE_RATE_HZ, CHANNEL_OUT_CONFIG, AUDIO_FORMAT);

        /**
         * MODE_STREAM：通过write一次次把音频数据写到AudioTrack中的内部缓冲区，可能会引起延时
         * MODE_STATIC：在play之前先把所有数据通过一次write调用传递到AudioTrack中的内部缓冲区，适合内存占用小，延时要求比较高的文件
         */
        int mode = AudioTrack.MODE_STREAM;
        //创建AudioTrack对象
        audioTrack = new AudioTrack(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build(),
                new AudioFormat.Builder().setSampleRate(SAMPLE_RATE_HZ)
                        .setEncoding(AUDIO_FORMAT)
                        .setChannelMask(CHANNEL_OUT_CONFIG)
                        .build(),
                minBufferSize, mode, AudioManager.AUDIO_SESSION_ID_GENERATE);
        //开始播放
        audioTrack.play();

        FileInputStream fileInputStream;
        try {
            fileInputStream = new FileInputStream(file);
            byte[] tempBuffer = new byte[minBufferSize];
            while (fileInputStream.available() > 0) {
                int readCount = fileInputStream.read(tempBuffer);
                if (readCount == AudioTrack.ERROR_INVALID_OPERATION ||
                        readCount == AudioTrack.ERROR_BAD_VALUE) {
                    continue;
                }
                if (readCount != 0 && readCount != -1) {
                    audioTrack.write(tempBuffer, 0, readCount);
                }
            }
            stopPlay();
        } catch (IOException e) {
            e.printStackTrace();
            stopPlay();
            Log.e(TAG, "startPlay Exception = " + e.toString());
        }
    }

    /**
     * 停止播放
     */
    private void stopPlay() {
        if (audioTrack != null) {
            audioTrack.stop();
            audioTrack.release();
            audioTrack = null;
            Log.i(TAG, "stopPlay");

            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    btn_play.setText(getString(R.string.start_play));
                }
            });
        }
    }
}
