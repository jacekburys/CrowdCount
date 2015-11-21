package com.crowdcount.crowdcount2;

import android.app.Activity;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.ImageView;
import android.widget.TextView;

import com.flir.flironesdk.*;


import org.opencv.android.Utils;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;


public class MainActivity extends Activity  implements Device.Delegate, FrameProcessor.Delegate {

    private Device flirDevice;
    private FrameProcessor frameProcessor;

    private boolean processing = false;

    synchronized private void setProcessing(boolean processing){
        this.processing = processing;
    }

    synchronized private boolean getProcessing() {
        return processing;
    }

    static { System.loadLibrary("opencv_java3"); }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        frameProcessor = new FrameProcessor(this, this,
                EnumSet.of(RenderedImage.ImageType.BlendedMSXRGBA8888Image));

    }

    @Override
    protected void onResume() {
        super.onResume();
        Device.startDiscovery(this, this);
    }

    @Override
    protected void onPause() {
        super.onPause();
        Device.stopDiscovery();
    }



    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();

        //noinspection SimplifiableIfStatement
        if (id == R.id.action_settings) {
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    // DEVICE DELEGATE

    @Override
    public void onTuningStateChanged(Device.TuningState tuningState) {

    }

    @Override
    public void onAutomaticTuningChanged(boolean b) {

    }

    @Override
    public void onDeviceConnected(Device device) {
        flirDevice = device;
        device.startFrameStream(new Device.StreamDelegate() {
            @Override
            public void onFrameReceived(Frame frame) {
                if(getProcessing()) return;
                setProcessing(true);
                frameProcessor.processFrame(frame);
            }
        });
    }

    @Override
    public void onDeviceDisconnected(Device device) {

    }

    // FRAME PROCESSOR

    @Override
    public void onFrameProcessed(RenderedImage renderedImage) {

        Bitmap bitmap = Bitmap.createBitmap(renderedImage.width(),
                renderedImage.height(),
                Bitmap.Config.ARGB_8888);
        bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(renderedImage.pixelData()));


        Mat mat = new Mat(renderedImage.height(), renderedImage.width(), CvType.CV_8UC4);
        mat.put(0, 0, renderedImage.pixelData());

        Imgproc.cvtColor(mat, mat, Imgproc.COLOR_BGR2GRAY);

        Imgproc.GaussianBlur(mat, mat, new Size(5, 5), 0);
        Imgproc.threshold(mat, mat, 0, 255, Imgproc.THRESH_OTSU);

        Mat dist = new Mat(renderedImage.height(), renderedImage.width(), CvType.CV_8UC4);

        Imgproc.distanceTransform(mat, dist, Imgproc.DIST_FAIR, Imgproc.DIST_MASK_PRECISE);
        Core.normalize(dist, dist, 0, 1, Core.NORM_MINMAX);


        Mat dist_8u = new Mat(renderedImage.height(), renderedImage.width(), CvType.CV_8U);
        dist.convertTo(dist_8u, CvType.CV_8U);

        List<MatOfPoint> contours = new ArrayList<MatOfPoint>();



        Imgproc.findContours(dist_8u, contours, new Mat(), Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE);

        final int count;
        if(contours != null){
            count = contours.size();
        }else{
            count = 0;
        }

        final TextView textView = (TextView)findViewById(R.id.textView);

        //convert back to color
        Imgproc.cvtColor(mat, mat, Imgproc.COLOR_GRAY2BGR);


        for(int i = 0; i<count; i++){
            Imgproc.drawContours(mat, contours, i, new Scalar(255, 0, 0), 5);
        }



        Utils.matToBitmap(mat, bitmap);

        final Bitmap imageBitmap = bitmap;

        final ImageView imageView = (ImageView)findViewById(R.id.imageView);
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                textView.setText(count + "objects");
                imageView.setImageBitmap(imageBitmap);

            }
        });

        setProcessing(false);
    }

}
