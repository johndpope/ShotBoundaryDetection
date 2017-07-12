/*
 * Andrew Hunziker
 * Java: 1.8.0_101
 * JavaCV: 1.2
 */

import javafx.scene.layout.BorderPane;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.FrameGrabber;
import org.bytedeco.javacv.Java2DFrameConverter;

import javafx.application.Platform;
import javafx.embed.swing.JFXPanel;
import javafx.scene.Scene;
import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;
import javafx.scene.media.MediaView;

import javax.swing.*;

import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.image.BufferedImage;
import java.awt.image.ColorModel;
import java.awt.image.PixelGrabber;
import java.awt.image.WritableRaster;

import java.io.File;
import java.util.ArrayList;
import java.util.Map;
import java.util.TreeMap;
import javafx.util.Pair;
import javafx.util.Duration;


public class ShotBoundaryDetection extends JFrame {

    /* assignment constants */
    private static final String PATH_TO_VIDEO = "video.mp4";
    private static final int START_FRAME = 1000;
    private static final int STOP_FRAME = 4999;
    private FFmpegFrameGrabber frmGrabber;

    /* intensity histogram */
    private static final int INTENSITY_BINS = 25;
    private static final double INTENSITY_RED_WEIGHT = 0.299;
    private static final double INTENSITY_GREEN_WEIGHT = 0.587;
    private static final double INTENSITY_BLUE_WEIGHT = 0.114;
    private int[][] intensityHistograms = new int[STOP_FRAME - START_FRAME + 1][INTENSITY_BINS];

    /* shot boundary detection */
    private int[] SD = new int[STOP_FRAME - START_FRAME];
    private double Tb = 0.0;
    private double Ts = 0.0;
    private static final int Tor = 2;

    private TreeMap<Integer, Pair<Integer, Integer>> cutList = new TreeMap<>();
    private TreeMap<Integer, Pair<Integer, Integer>> gtList = new TreeMap<>();

    /* GUI components */
    private static final int DEFAULT_HEIGHT = 750;
    private static final int DEFAULT_WIDTH = 900;
    private static final double FPS = 25.0;
    private static final int OFFSET = 10; // accounts for the difference between frame # and seek by MS
    private MediaPlayer mediaPlayer;


    public static void main(String[] args) {

        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                ShotBoundaryDetection sbd = new ShotBoundaryDetection();
            }
        });

    }

    private ShotBoundaryDetection() {
        /* set up GUI */
        /* init frame */
        setVisible(true);
        setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        setLayout(new GridLayout(1, 2, 5, 0));
        setTitle("Please wait while the video is processed");
        setSize(DEFAULT_WIDTH, DEFAULT_HEIGHT);
        setResizable(false);
        setLocationRelativeTo(null);

        /* init left panel and components */
        final JFXPanel leftVideoPanel = new JFXPanel();

        /* init right panel and components */
        JPanel rightPanel = new JPanel();
        rightPanel.setLayout(new GridLayout(1, 1));
        JPanel shotPanel = new JPanel();
        shotPanel.setLayout(new BoxLayout(shotPanel, BoxLayout.PAGE_AXIS));
        JScrollPane shotScrollPane = new JScrollPane(shotPanel, ScrollPaneConstants.VERTICAL_SCROLLBAR_ALWAYS, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        JScrollBar shotScrollBar = new JScrollBar();
        shotScrollPane.getVerticalScrollBar().setUnitIncrement(20);
        ArrayList<JButton> shotPlayButtons = new ArrayList<>();

        /* add left panel and components */
        add(leftVideoPanel);

        /* add right panel and components */
        add(rightPanel);
        shotScrollPane.add(shotScrollBar);
        rightPanel.add(shotScrollPane);

        /* parse video & compute shot boundaries */
        frmGrabber = new FFmpegFrameGrabber(PATH_TO_VIDEO);
        VideoParser vp = new VideoParser();
        vp.parse();
        calculateSD();
        setThresholds();
        findTransitions();
        outputTransitions();

        /* add first frame of each shot to GUI */
        setTitle("Shot Boundary Detection");
        TreeMap<Integer, Pair<Integer, Integer>> scenes = new TreeMap<>();
        scenes.putAll(cutList);
        scenes.putAll(gtList);

        /* get scene start frames and make buttons */
        Java2DFrameConverter frmCvt = new Java2DFrameConverter();
        for (Map.Entry<Integer, Pair<Integer, Integer>> entry : scenes.entrySet()) {
            try {
                frmGrabber.setFrameNumber(entry.getKey() + 1);
            }
            catch (FrameGrabber.Exception e) {
                e.printStackTrace();
                System.exit(1);
            }

            org.bytedeco.javacv.Frame frm = null;
            try {
                frm = frmGrabber.grabImage();
            }
            catch (FrameGrabber.Exception e) {
                System.err.println("failed to grab");
                System.exit(1);
            }

            BufferedImage img = frmCvt.getBufferedImage(frm);
            ImageIcon icon = new ImageIcon(deepCopy(img));
            JButton button;

            Integer end = scenes.higherKey(entry.getKey());
            if (end != null) {
                button = new JButton(entry.getKey() + 1 + ", " + end, icon);
                button.addActionListener(new ShotPlayButtonHandler(entry.getKey() + 1, end));
            } else {
                button = new JButton(entry.getKey() + 1 + ", " + STOP_FRAME, icon);
                button.addActionListener(new ShotPlayButtonHandler(entry.getKey() + 1, STOP_FRAME));
            }
            shotPlayButtons.add(button);
        }

        /* add buttons */
        for (JButton button : shotPlayButtons) {
            shotPanel.add(button);
        }

        /* close video, done grabbing frames */
        try {
            frmGrabber.stop();
        }
        catch (FrameGrabber.Exception e) {
            System.err.println("failed to stop video");
            e.printStackTrace();
            System.exit(1);
        }

        /* start JavaFX thread and init JFXPanel */
        Platform.runLater(new Runnable() { // TODO
            @Override
            public void run() {
                initVideoPanel(leftVideoPanel);
            }
        });
    }

    /* This method initializes the JavaFX panel used to play videos
     * NOTE: This uses some code almost verbatim from a Sum/Oracle tutorial & IDR Solutions tutorial
     * https://docs.oracle.com/javase/8/javafx/interoperability-tutorial/swing-fx-interoperability.htm
     * https://blog.idrsolutions.com/2014/11/write-media-player-javafx-using-netbeans-ide-part-2/
     */
    private void initVideoPanel(JFXPanel videoPanel) {
        Scene scene = createScene();
        videoPanel.setScene(scene);
    }

    /* This method creates the scene for the JavaFX panel
     * NOTE: This uses some code almost verbatim from a Sum/Oracle tutorial & IDR Solutions tutorial
     * https://docs.oracle.com/javase/8/javafx/interoperability-tutorial/swing-fx-interoperability.htm
     * https://blog.idrsolutions.com/2014/11/write-media-player-javafx-using-netbeans-ide-part-2/
     */
    private Scene createScene() {

        Media media = new Media(new File(PATH_TO_VIDEO).toURI().toString());
        mediaPlayer = new MediaPlayer(media);
        mediaPlayer.setAutoPlay(false);
        MediaView mediaView = new MediaView(mediaPlayer);

        BorderPane borderPane = new BorderPane();
        borderPane.setCenter(mediaView);
        return new Scene(borderPane);
    }

    /* This class implements the ActionListener for the shot play buttons
     */
    private class ShotPlayButtonHandler implements ActionListener {

        int shotStartFrame;
        int shotEndFrame;

        ShotPlayButtonHandler(int start, int end) {
            shotStartFrame = start;
            shotEndFrame = end;
        }

        public void actionPerformed(ActionEvent e) {
            Platform.runLater(new Runnable() { // TODO
                public void run() {
                    mediaPlayer.seek(Duration.millis(((shotStartFrame + OFFSET) / FPS) * 1000.0));
                    mediaPlayer.play();
                }
            });
        }

    }

    /* This method calculated the frame-to-frame differences based on the intensity histogram for the frames
     * SD[i] = |f(i) - f(i + 1)| where f(i) is the sum of all bins of the intensity histogram
     */
    private void calculateSD() {
        for (int i = 0; i < STOP_FRAME - START_FRAME; ++i) {
            for (int j = 0; j < INTENSITY_BINS; ++j) {
                SD[i] += Math.abs(intensityHistograms[i][j] - intensityHistograms[i + 1][j]);
            }
        }
    }

    /* This method calculated the threshold vales Ts and Tb;
     */
    private void setThresholds() {
        double meanSD = 0.0;
        for (int i = 0; i < STOP_FRAME - START_FRAME; ++i) {
            meanSD += SD[i];
        }
        meanSD /= (double)(STOP_FRAME - START_FRAME);

        double stdSD = 0.0;
        for (int i = 0; i < STOP_FRAME - START_FRAME; ++i) {
            stdSD += Math.pow(SD[i] - meanSD, 2);
        }
        stdSD *= (1.0 / ((double)(STOP_FRAME - START_FRAME - 1))); // NOTE: std using n - 1
        stdSD = Math.sqrt(stdSD);

        Ts = meanSD * 2.0;
        Tb = meanSD + (stdSD * 11.0);
        // System.out.println("meanSD: " + meanSD + " stdSD: " + stdSD + " Ts: " + Ts + " Tb: " + Tb);
    }

    /* This method calculates shot boundary transitions using the twin comparison boundary detection algorithm
     * Cuts = {Cs, Ce} where SD(Cs) >= Tb; Gradual Transitions = {Fs, Fe} where sum(SD(Fs, Fe)) >= Tb, Fs >= Ts,
     * and no sequential series of frames >= Tor are <= Ts
     */
    private void findTransitions() {
        // find cuts, Cs >= Tb
        for (int i = 0; i < STOP_FRAME - START_FRAME; ++i) {
            if (SD[i] >= Tb) {
                cutList.put(i + START_FRAME, new Pair<>(i + START_FRAME, i + START_FRAME + 1));
            }
        }

        // find gradual transitions, sum(SD(Fs, Fe)) >= Tb, Fs >= Ts, and no sequential series of frames >= Tor are <= Ts
        int FsCandidate = -1, FeCandidate = -1, belowTs = 0;
        for (int i = 0; i < STOP_FRAME - START_FRAME; ++i) {
            if(SD[i] >= Ts && SD[i] < Tb) {
                FsCandidate = i;
                for (int j = i + 1; j < STOP_FRAME - START_FRAME && belowTs < Tor; ++j) {
                    if (SD[j] >= Ts && SD[j] < Tb) {
                        FeCandidate = j;
                        belowTs = 0;
                    } else if (SD[j] >= Tb) {
                        FeCandidate = j - 1;
                        i = j;
                        break; // terminate inner loop; reached cut
                    } else {
                        ++belowTs;
                    }
                }

                // check if sum SD gradual transition candidate >= Tb
                if (FeCandidate > FsCandidate) {
                    int sumSD = 0;
                    for (int k = FsCandidate; k <= FeCandidate; ++k) {
                        sumSD += SD[k];
                    }
                    if (sumSD >= Tb) {
                        gtList.put(FsCandidate + START_FRAME, new Pair<>(FsCandidate + START_FRAME, FeCandidate + START_FRAME));
                    }
                    i = FeCandidate + 1;
                }
                FsCandidate = -1; FeCandidate = -1; belowTs = 0;
            }
        }
    }

    /* This method outputs the start of every new scene (Ce or Fs + 1) to stdout
     */
    private void outputTransitions() {
        System.out.println("Ce");
        for (Map.Entry<Integer, Pair<Integer, Integer>> entry : cutList.entrySet()) {
            System.out.println(entry.getKey() + 1);
        }

        System.out.println("Fs + 1");
        for (Map.Entry<Integer, Pair<Integer, Integer>> entry : gtList.entrySet()) {
            System.out.println(entry.getKey() + 1);
        }
    }

    /* This method crates a deep copy of a BufferedImage
     * from Klark @ StackOverflow
     * https://stackoverflow.com/questions/3514158/how-do-you-clone-a-bufferedimage
     */
    private static BufferedImage deepCopy(BufferedImage bi) {
        ColorModel cm = bi.getColorModel();
        boolean isAlphaPremultiplied = cm.isAlphaPremultiplied();
        WritableRaster raster = bi.copyData(null);
        return new BufferedImage(cm, raster, isAlphaPremultiplied, null);
    }

    /* The VideoParser class parses the video file to create an intensity histogram for each frame
     */
    private class VideoParser {

        private VideoParser() { }

        /* This method parses the video file and calculates an intensity histogram
         * for each frame in range [START_FRAME, STOP_FRAME]
         */
        private void parse() {
            try {
                frmGrabber.start();
            }
            catch (FrameGrabber.Exception e) {
                System.err.println("failed to open the video");
                e.printStackTrace();
                System.exit(1);
            }

            // get all frames in range [START_FRAME, STOP_FRAME]
            Java2DFrameConverter frmCvt = new Java2DFrameConverter();
            for (int i = START_FRAME; i <= STOP_FRAME; ++i) {
                try {
                    frmGrabber.setFrameNumber(i);
                }
                catch (FrameGrabber.Exception e) {
                    System.err.println("failed to advance frame");
                    e.printStackTrace();
                    System.exit(1);
                }

                org.bytedeco.javacv.Frame frm = null;
                try {
                    frm = frmGrabber.grabImage();
                }
                catch (FrameGrabber.Exception e) {
                    System.err.println("failed to grab");
                    System.exit(1);
                }

                // get pixels from this frame
                BufferedImage img = frmCvt.getBufferedImage(frm);
                PixelGrabber pxlGrabber = new PixelGrabber(img, 0, 0, -1, -1, true);
                try {
                    pxlGrabber.grabPixels();
                } catch (InterruptedException e) {
                    System.err.println("error grabbing pixels");
                    e.printStackTrace();
                    System.exit(1);
                }

                // get RGB values for each pixel & build histogram, NOTE: assumes 24 bit RGB
                int rgb[] = (int[]) pxlGrabber.getPixels();
                int red, green, blue;

                for (int j = 0; j < pxlGrabber.getWidth() * pxlGrabber.getHeight(); ++j) {
                    red = (rgb[j] >> 16) & 0xff;
                    green = (rgb[j] >> 8) & 0xff;
                    blue = rgb[j] & 0xff;

                    // histogram bins from [0,10), [10,20) ... [240,255]
                    int intensity = getPixelIntensity(red, green, blue);
                    if(intensity < 240) { // last bin is [240,255]
                        intensityHistograms[i - START_FRAME][intensity / 10]++;
                    } else {
                        intensityHistograms[i - START_FRAME][INTENSITY_BINS - 1]++;
                    }
                }
            }
        }

        /* This method returns the intensity value of a given pixel
         */
        private int getPixelIntensity(int red, int green, int blue) {
            return (int) ((INTENSITY_RED_WEIGHT * red) +
                          (INTENSITY_GREEN_WEIGHT * green) +
                          (INTENSITY_BLUE_WEIGHT * blue));
        }

    }


}
