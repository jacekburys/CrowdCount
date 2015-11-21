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
import org.opencv.core.Point;
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
    public void onFrameProcessed(final RenderedImage renderedImage) {

        // image size : 480 x 640

        int width = 240;
        int height = 320;

        Mat orig = new Mat(renderedImage.height(), renderedImage.width(), CvType.CV_8UC4);
        orig.put(0, 0, renderedImage.pixelData());

        Mat mat = new Mat(height, width, CvType.CV_8UC4);
        Imgproc.resize(orig, mat, new Size(width, height));

        Imgproc.cvtColor(mat, mat, Imgproc.COLOR_BGR2GRAY);

        Imgproc.GaussianBlur(mat, mat, new Size(5, 5), 0);
        Imgproc.threshold(mat, mat, 0, 255, Imgproc.THRESH_OTSU);

        Mat dist = new Mat(height, width, CvType.CV_8UC4);

        Imgproc.distanceTransform(mat, dist, Imgproc.DIST_FAIR, Imgproc.DIST_MASK_PRECISE);
        Core.normalize(dist, dist, 0, 1, Core.NORM_MINMAX);
        Imgproc.threshold(dist, dist, 0.5, 1, Imgproc.THRESH_BINARY);


        Mat dist_8u = new Mat(height, width, CvType.CV_8U);
        dist.convertTo(dist_8u, CvType.CV_8U);

        List<MatOfPoint> contours = new ArrayList<MatOfPoint>();



        Imgproc.findContours(dist_8u, contours, new Mat(), Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE);

        final int count;
        if(contours != null){
            count = contours.size();
        }else{
            count = 0;
        }



        Mat markers = Mat.zeros(dist.size(), CvType.CV_32SC1);

        //convert back to color
        //Imgproc.cvtColor(mat, mat, Imgproc.COLOR_GRAY2BGR);


        for(int i = 0; i<count; i++){
            Imgproc.drawContours(markers, contours, i, Scalar.all(i+1), -1);
        }

        boolean back = false;
        int backX = 0;
        int backY = 0;

        for(int i = 3; i<dist.rows()-3; i++){
            for(int j = 3; j<dist.cols()-3; j++){
                if(back) break;
                if(dist.get(i, j)[0] < 0.5){
                    back = true;
                    backX = j;
                    backY = i;
                }
            }
            if(back) break;
        }

        final int x = backX;
        final int y = backY;

        //marker for background
        Imgproc.circle(markers, new Point(backY, backX), 3, new Scalar(255,255,255), -1);
        //Imgproc.watershed(mat, markers);


        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Utils.matToBitmap(mat, bitmap);

        final Bitmap imageBitmap = bitmap;
        final TextView textView = (TextView)findViewById(R.id.textView);
        final ImageView imageView = (ImageView)findViewById(R.id.imageView);
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                textView.setText(x + ", " + y);
                imageView.setImageBitmap(imageBitmap);

            }
        });

        setProcessing(false);
    }

}
