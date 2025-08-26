package xyz.theforks.ckndi;

import heronarts.glx.ui.UI2dContainer;
import heronarts.glx.ui.component.UIButton;
import heronarts.glx.ui.component.UIKnob;
import heronarts.glx.ui.component.UILabel;
import heronarts.glx.ui.vg.VGraphics;
import heronarts.lx.LX;
import heronarts.lx.LXCategory;
import heronarts.lx.LXComponentName;
import heronarts.lx.color.LXColor;
import heronarts.lx.parameter.*;
import heronarts.lx.pattern.LXPattern;
import heronarts.lx.studio.LXStudio;
import heronarts.lx.studio.ui.device.UIDevice;
import heronarts.lx.studio.ui.device.UIDeviceControls;
import me.walkerknapp.devolay.*;

import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * NDI pattern using devolay library exclusively
 * Receives NDI video streams and maps them to LED fixtures using UV
 * coordinates.
 */
@LXCategory("Custom")
@LXComponentName("CkNDI")
public class CkNDI extends LXPattern implements UIDeviceControls<CkNDI> {

    public final StringParameter ndiSourceName = new StringParameter("NDI Source", "")
            .setDescription("Selected NDI source name");

    public final BooleanParameter autoConnect = new BooleanParameter("Auto Connect", true)
            .setDescription("Automatically connect when pattern becomes active");

    // UV mapping parameters
    public final CompoundParameter uOffset = new CompoundParameter("uOff", 0, -1, 1)
            .setDescription("U Offset");
    public final CompoundParameter vOffset = new CompoundParameter("vOff", 0, -1, 1)
            .setDescription("V Offset");
    public final CompoundParameter uWidth = new CompoundParameter("uWidth", 1, 0, 2)
            .setDescription("U Width");
    public final CompoundParameter vHeight = new CompoundParameter("vHeight", 1, 0, 2)
            .setDescription("V Height");
    public final CompoundParameter rotate = new CompoundParameter("Rotate", 0, 0, 1)
            .setDescription("Rotate uv coordinates");
    public final DiscreteParameter tileX = new DiscreteParameter("TileX", 1, 1, 10)
            .setDescription("Tile X");
    public final DiscreteParameter tileY = new DiscreteParameter("TileY", 1, 1, 10)
            .setDescription("Tile Y");
    public final BooleanParameter flipHorizontal = new BooleanParameter("FlipX", false);
    public final BooleanParameter flipVertical = new BooleanParameter("FlipY", false);

    // NDI components
    private DevolayFinder finder;
    private DevolayReceiver receiver;
    private List<DevolaySource> availableSources = new ArrayList<>();
    private BufferedImage currentFrame;
    private Thread ndiThread;
    private volatile boolean running = false;
    
    // Initialization retry mechanism
    private Thread initializationThread;
    private volatile boolean needsInitialization = false;
    private int retryCount = 0;
    private static final int MAX_RETRIES = 10;
    private static final long RETRY_DELAY_MS = 1000; // 1 second between retries

    // UI components
    private UIButton sourceSelectButton;
    private UIButton refreshButton;
    private int currentSourceIndex = -1;

    // UV mapping
    private List<UVPoint> uvPoints = null;
    private boolean uvsNeedUpdate = true;

    public final boolean VERBOSE = false;

    public CkNDI(LX lx) {
        super(lx);
        addParameter("ndiSource", this.ndiSourceName);
        addParameter("autoConnect", this.autoConnect);
        addParameter("uOff", this.uOffset);
        addParameter("vOff", this.vOffset);
        addParameter("uWidth", this.uWidth);
        addParameter("vHeight", this.vHeight);
        addParameter("flipX", this.flipHorizontal);
        addParameter("flipY", this.flipVertical);
        addParameter("rotate", this.rotate);
        addParameter("tileX", this.tileX);
        addParameter("tileY", this.tileY);

        // Initialize devolay
        initializeDevolay();
        model.addListener((p)-> {
           computeUVs();
        });
    }

    private void initializeDevolay() {
        try {
            if (VERBOSE)
                LX.log("Initializing Devolay NDI library...");

            // Initialize devolay
            Devolay.loadLibraries();

            finder = new DevolayFinder();
            refreshNDISources();

            if (VERBOSE)
                LX.log("Devolay initialization complete");
        } catch (Exception e) {
            LX.error(e, "Failed to initialize Devolay");
        }
    }

    private void refreshNDISources() {
        if (finder == null)
            return;

        try {
            if (VERBOSE)
                LX.log("Refreshing NDI sources using devolay...");

            availableSources.clear();
            DevolaySource[] sources = finder.getCurrentSources(); // Get current sources

            for (DevolaySource source : sources) {
                availableSources.add(source);
            }

            if (VERBOSE) {
                LX.log("Found " + availableSources.size() + " NDI sources via devolay:");
                for (DevolaySource source : availableSources) {
                    LX.log("  - " + source.getSourceName());
                }
            }
        } catch (Exception e) {
            LX.error(e, "Failed to refresh NDI sources via devolay");
        }
    }

    private void startNDIReceiver() {
        if (ndiSourceName.getString().isEmpty()) {
            if (VERBOSE)
                LX.log("No NDI source selected");
            return;
        }

        // Find the selected source - refresh sources first if not found
        DevolaySource selectedSource = null;
        String targetSourceName = ndiSourceName.getString();

        // First try to find in current list
        for (DevolaySource source : availableSources) {
            if (source.getSourceName().equals(targetSourceName)) {
                selectedSource = source;
                break;
            }
        }

        // If not found, refresh and try again
        if (selectedSource == null) {
            if (VERBOSE)
                LX.log("Source not found in cache, refreshing NDI sources...");
            refreshNDISources();

            for (DevolaySource source : availableSources) {
                if (source.getSourceName().equals(targetSourceName)) {
                    selectedSource = source;
                    break;
                }
            }
        }

        if (selectedSource == null) {
            LX.error("Could not find NDI source: " + targetSourceName + " (even after refresh)");
            return;
        }

        try {
            if (VERBOSE)
                LX.log("Starting NDI receiver for: " + selectedSource.getSourceName());

            // Create receiver with default settings (like in example)
            receiver = new DevolayReceiver(DevolayReceiver.ColorFormat.BGRX_BGRA,
                    DevolayReceiver.RECEIVE_BANDWIDTH_HIGHEST,
                    true, "CkNDI");

            // Connect to the selected source (like in example)
            receiver.connect(selectedSource);

            // Start receiving thread
            running = true;
            ndiThread = new Thread(this::ndiReceiveLoop);
            ndiThread.setName("NDI-Receiver-" + selectedSource.getSourceName());
            ndiThread.start();

            if (VERBOSE)
                LX.log("NDI receiver started successfully");

        } catch (Exception e) {
            LX.error(e, "Failed to start NDI receiver");
        }
    }

    private void stopNDIReceiver() {
        try {
            running = false;

            if (ndiThread != null) {
                try {
                    ndiThread.interrupt();
                    ndiThread.join(1); // Wait up to 1 second
                } catch (InterruptedException e) {
                    // Ignore
                }
                ndiThread = null;
            }

            LX.log("Closing receiver");
            if (receiver != null) {
                try {
                    //receiver.connect(null); // Disconnect like in example
                    //receiver = null;
                    receiver.close();
                } catch (Throwable e) {
                    LX.error(e, "Error closing NDI receiver");
                }
                receiver = null;
            }

            currentFrame = null;
            if (VERBOSE)
                LX.log("NDI receiver stopped");
        } catch (Throwable t) {
            LX.log("Caught throwable: " + t.getMessage());
        }
    }

    private void ndiReceiveLoop() {
        DevolayVideoFrame videoFrame = new DevolayVideoFrame();
        long frameCount = 0;
        long lastLogTime = System.currentTimeMillis();

        LX.log("NDI receive loop started, waiting for frames...");

        while (running && !Thread.currentThread().isInterrupted()) {
            try {
                // Use receiveCapture like in the example (with 0 timeout for non-blocking)
                DevolayFrameType frameType = receiver.receiveCapture(videoFrame, null, null, 5);

                if (!running) break; 

                if (frameType == DevolayFrameType.VIDEO) {
                    frameCount++;
                    long currentTime = System.currentTimeMillis();

                    // Log every 30 frames or every 5 seconds
                    if (frameCount % 30 == 0 || (currentTime - lastLogTime > 5000)) {
                        if (VERBOSE) {
                            LX.log("Received NDI video frame #" + frameCount + ", resolution: " +
                                    videoFrame.getXResolution() + "x" + videoFrame.getYResolution());
                        }
                        lastLogTime = currentTime;
                    }

                    processVideoFrame(videoFrame);
                } else if (frameType == DevolayFrameType.NONE) {
                    // No frame available - add small delay to avoid busy loop
                    if (frameCount == 0) {
                        // Log only if we haven't received any frames yet
                        long currentTime = System.currentTimeMillis();
                        if (currentTime - lastLogTime > 2000) {
                            LX.log("Still waiting for NDI frames (no data available)...");
                            lastLogTime = currentTime;
                        }
                    }
                    Thread.sleep(10); // Small delay to prevent busy waiting
                } else {
                    // Connection lost or other error
                    LX.log("NDI receive unexpected frame type: " + frameType);
                }
            } catch (IllegalArgumentException e) {
                if (e.getMessage() != null && e.getMessage().contains("Unknown frame type id")) {
                    // Devolay doesn't recognize this frame type - continue receiving
                    if (VERBOSE)
                        LX.log("Devolay unknown frame type (continuing): " + e.getMessage());
                    // Thread.sleep(1); // Small delay to prevent tight loop
                } else {
                    if (running) {
                        LX.error(e, "IllegalArgumentException in NDI receive loop");
                    }
                    break;
                }
            } catch (Exception e) {
                if (running) {
                    LX.error(e, "Error in NDI receive loop");
                }
                break;
            }
        }

        videoFrame.close();
        LX.log("NDI receive loop exited. Total frames received: " + frameCount);
    }

    private void processVideoFrame(DevolayVideoFrame videoFrame) {
        try {
            int width = videoFrame.getXResolution();
            int height = videoFrame.getYResolution();

            if (width <= 0 || height <= 0)
                return;

            // Get the frame data
            ByteBuffer frameData = videoFrame.getData();
            if (frameData == null)
                return;

            // Convert to BufferedImage
            BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);

            // Convert BGRX to RGB
            byte[] pixelData = new byte[frameData.remaining()];
            frameData.get(pixelData);

            int pixelIndex = 0;
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    if (pixelIndex + 3 < pixelData.length) {
                        int b = pixelData[pixelIndex] & 0xFF;
                        int g = pixelData[pixelIndex + 1] & 0xFF;
                        int r = pixelData[pixelIndex + 2] & 0xFF;
                        // Skip alpha channel (pixelIndex + 3)

                        int rgb = (r << 16) | (g << 8) | b;
                        image.setRGB(x, y, rgb);
                        pixelIndex += 4; // BGRX format
                    }
                }
            }

            synchronized (this) {
                currentFrame = image;
            }

        } catch (Exception e) {
            LX.error(e, "Error processing NDI video frame");
        }
    }

    private void cycleToNextSource() {
        if (availableSources.isEmpty()) {
            refreshNDISources();
            if (availableSources.isEmpty()) {
                ndiSourceName.setValue("");
                currentSourceIndex = -1;
                updateSourceButton();
                return;
            }
        }

        currentSourceIndex = (currentSourceIndex + 1) % availableSources.size();
        String sourceName = availableSources.get(currentSourceIndex).getSourceName();
        ndiSourceName.setValue(sourceName);
        updateSourceButton();
    }

    private void updateSourceButton() {
        if (sourceSelectButton == null)
            return;

        String currentSource = ndiSourceName.getString();
        if (currentSource.isEmpty()) {
            sourceSelectButton.setLabel("No NDI Source");
        } else {
            String displayName = currentSource.length() > 30
                    ? currentSource.substring(0, 3) + "..." + currentSource.substring(currentSource.length() - 24)
                    : currentSource;
            sourceSelectButton.setLabel(displayName);
        }

        // Update current index to match selected source
        currentSourceIndex = -1;
        for (int i = 0; i < availableSources.size(); i++) {
            if (availableSources.get(i).getSourceName().equals(currentSource)) {
                currentSourceIndex = i;
                break;
            }
        }
    }

    @Override
    public void onParameterChanged(LXParameter p) {
        super.onParameterChanged(p);
        if (p == ndiSourceName) {
            // Restart receiver with new source
            stopNDIReceiver();
            if (autoConnect.isOn() && !ndiSourceName.getString().isEmpty()) {
                startNDIReceiver();
            }
        }
        // UV parameters don't need special handling - they're used in run()
    }

    @Override
    public void onActive() {
        super.onActive();
        uvsNeedUpdate = true;
        if (autoConnect.isOn() && !ndiSourceName.getString().isEmpty()) {
            // Start delayed initialization with retries
            startDelayedInitialization();
        }
    }
    
    private void startDelayedInitialization() {
        if (initializationThread != null && initializationThread.isAlive()) {
            return; // Already trying to initialize
        }
        
        needsInitialization = true;
        retryCount = 0;
        
        initializationThread = new Thread(() -> {
            while (needsInitialization && retryCount < MAX_RETRIES) {
                try {
                    // Wait a bit for NDI sources to become available
                    if (retryCount == 0) {
                        Thread.sleep(500); // Initial delay
                    } else {
                        Thread.sleep(RETRY_DELAY_MS);
                    }
                    
                    if (!needsInitialization) break;
                    
                    // Refresh sources and try to connect
                    refreshNDISources();
                    
                    String targetSource = ndiSourceName.getString();
                    boolean sourceFound = false;
                    
                    for (DevolaySource source : availableSources) {
                        if (source.getSourceName().equals(targetSource)) {
                            sourceFound = true;
                            break;
                        }
                    }
                    
                    if (sourceFound) {
                        LX.log("NDI source found after " + (retryCount + 1) + " attempt(s): " + targetSource);
                        startNDIReceiver();
                        needsInitialization = false;
                        break;
                    } else {
                        retryCount++;
                        if (VERBOSE || retryCount == 1 || retryCount == MAX_RETRIES) {
                            LX.log("NDI source not yet available, attempt " + retryCount + "/" + MAX_RETRIES + ": " + targetSource);
                        }
                    }
                } catch (InterruptedException e) {
                    break;
                } catch (Exception e) {
                    LX.error(e, "Error during NDI initialization retry");
                    break;
                }
            }
            
            if (needsInitialization && retryCount >= MAX_RETRIES) {
                LX.error("Failed to find NDI source after " + MAX_RETRIES + " attempts: " + ndiSourceName.getString());
            }
        });
        
        initializationThread.setName("NDI-Init-Retry");
        initializationThread.start();
    }

    @Override
    public void onInactive() {
        super.onInactive();
        
        // Stop any pending initialization
        needsInitialization = false;
        if (initializationThread != null && initializationThread.isAlive()) {
            initializationThread.interrupt();
            try {
                initializationThread.join(1000);
            } catch (InterruptedException e) {
                // Ignore
            }
        }

        stopNDIReceiver();
        running = false;
    }

    @Override
    public void dispose() {
        stopNDIReceiver();

        if (finder != null) {
            try {
                finder.close();
            } catch (Exception e) {
                LX.error(e, "Error closing NDI finder");
            }
            finder = null;
        }

        super.dispose();
    }

    @Override
    protected void run(double deltaMs) {
        BufferedImage frame;
        synchronized (this) {
            frame = currentFrame;
        }

        if (frame == null) {
            return;
        }

        // Update UV points if needed
        if (uvsNeedUpdate) {
            computeUVs();
            uvsNeedUpdate = false;
        }

        // Render frame to LEDs using UV mapping
        renderWithUV(frame);
    }

    private void renderWithUV(BufferedImage frame) {
        int width = frame.getWidth();
        int height = frame.getHeight();
        float[] uvs = { 0f, 0f };

        for (UVPoint uv : uvPoints) {
            uvs[0] = uv.u;
            uvs[1] = uv.v;

            // Apply transformations
            if (flipHorizontal.isOn()) {
                uvs[0] = 1f - uvs[0];
            }
            if (flipVertical.isOn()) {
                uvs[1] = 1f - uvs[1];
            }
            if (tileX.getValuei() > 1) {
                uvs[0] = (uvs[0] * tileX.getValuei() - 0.01f) % 1f;
            }
            if (tileY.getValuei() > 1) {
                uvs[1] = (uvs[1] * tileY.getValuei() - 0.01f) % 1f;
            }
            if (rotate.getValuef() > 0) {
                rotateUV(uvs[0], uvs[1], rotate.getValuef() * (float) Math.PI * 2, uvs);
            }

            int x = Math.round((uOffset.getValuef() + uvs[0] * uWidth.getValuef()) * (width - 1));
            int y = Math.round((vOffset.getValuef() + uvs[1] * vHeight.getValuef()) * (height - 1));

            int color = 0;
            if (x >= 0 && x < width && y >= 0 && y < height) {
                color = frame.getRGB(x, y);
            }
            if (uv.point.index < colors.length) {
                colors[uv.point.index] = LXColor.rgb(LXColor.red(color), LXColor.green(color), LXColor.blue(color));
            } else {
                uvsNeedUpdate = true;
            }
        }
    }

    private void rotateUV(float u, float v, float rad, float[] results) {
        float x = u - 0.5f;
        float y = v - 0.5f;
        float cos = (float) Math.cos(rad);
        float sin = (float) Math.sin(rad);
        results[0] = x * cos - y * sin + 0.5f;
        results[1] = x * sin + y * cos + 0.5f;
    }

    private void computeUVs() {
        if (uvPoints == null || uvsNeedUpdate) {
            uvPoints = new ArrayList<>(model.points.length);
        } else {
            uvPoints.clear();
        }

        float[] planeNormal = UVUtil.computePlaneNormal(model);
        UVUtil.normalizePlaneNormal(planeNormal);

        float[] rotateAxisAngle = UVUtil.computeAxesRotates(planeNormal);
        float[] rotateAxis = { rotateAxisAngle[0], rotateAxisAngle[1], rotateAxisAngle[2] };
        float rotateAngle = rotateAxisAngle[3];
        float[] rotatedPoint = new float[3];

        // Only normalize if the axis has non-zero length
        float axisLength = UVUtil.vectorLength(rotateAxis);
        if (axisLength > 0.0001f) {
            UVUtil.normalizePlaneNormal(rotateAxis);
        }
        
        for (heronarts.lx.model.LXPoint p : model.points) {
            float[] point = { p.x, p.y, p.z };
            // Only rotate if we have a valid rotation (angle > 0 and valid axis)
            if (rotateAngle > 0.0001f && axisLength > 0.0001f) {
                UVUtil.rotatePointAroundAxis(point, rotateAxis, rotateAngle, rotatedPoint);
                UVPoint uv = new UVPoint(p, rotatedPoint[0], rotatedPoint[1]);
                uvPoints.add(uv);
            } else {
                // No rotation needed, use original coordinates
                UVPoint uv = new UVPoint(p, p.x, p.y);
                uvPoints.add(uv);
            }
        }

        UVPoint.renormalizeUVs(uvPoints);
    }

    @Override
    public void buildDeviceControls(LXStudio.UI ui, UIDevice uiDevice, CkNDI pattern) {
        uiDevice.setContentWidth(280);
        uiDevice.setLayout(UI2dContainer.Layout.VERTICAL);
        uiDevice.setPadding(5, 0);
        uiDevice.setChildSpacing(5);

        // NDI Source selection container
        final UI2dContainer sourceContainer = new UI2dContainer(0, 0, 270, 18);
        sourceContainer.addToContainer(uiDevice);

        new UILabel(0, 0, 60, 18)
                .setLabel("NDI Source:")
                .setTextAlignment(VGraphics.Align.LEFT, VGraphics.Align.MIDDLE)
                .addToContainer(sourceContainer);

        // Source selection button
        sourceSelectButton = (UIButton) new UIButton(65, 0, 170, 18) {
            @Override
            public void onToggle(boolean on) {
                if (on) {
                    cycleToNextSource();
                }
            }
        }
                .setMomentary(true)
                .setDescription("Click to cycle through NDI sources")
                .addToContainer(sourceContainer);
        updateSourceButton();

        // Refresh button
        refreshButton = (UIButton) new UIButton(250, 0, 18, 18) {
            @Override
            public void onToggle(boolean on) {
                if (on) {
                    if (VERBOSE)
                        LX.log("Refresh button pressed");
                    refreshNDISources();
                    updateSourceButton();
                }
            }
        }
                .setIcon(ui.theme.iconLoop)
                .setMomentary(true)
                .setDescription("Refresh NDI Sources")
                .addToContainer(sourceContainer);

        // Auto-connect button
        final UI2dContainer autoContainer = new UI2dContainer(0, 25, 150, 18);
        autoContainer.addToContainer(uiDevice);
        new UIButton(0, 0, 80, 18)
                .setParameter(pattern.autoConnect)
                .setLabel("Auto Connect")
                .addToContainer(autoContainer);

        // UV controls container
        final UI2dContainer uvContainer = (UI2dContainer) new UI2dContainer(0, 50, 270, 40)
                .setLayout(UI2dContainer.Layout.HORIZONTAL)
                .addToContainer(uiDevice);
        uvContainer.setPadding(5);
        uvContainer.setChildSpacing(5);
        new UIKnob(0, 0, 35, 30)
                .setParameter(pattern.uOffset)
                .addToContainer(uvContainer);
        new UIKnob(40, 0, 35, 30)
                .setParameter(pattern.vOffset)
                .addToContainer(uvContainer);
        new UIKnob(80, 0, 35, 30)
                .setParameter(pattern.uWidth)
                .addToContainer(uvContainer);
        new UIKnob(120, 0, 35, 30)
                .setParameter(pattern.vHeight)
                .addToContainer(uvContainer);
        new UIKnob(160, 0, 35, 30)
                .setParameter(pattern.rotate)
                .addToContainer(uvContainer);

        // Flip and tile controls
        final UI2dContainer controlsContainer = (UI2dContainer) new UI2dContainer(0, 95, 270, 40)
                .setLayout(UI2dContainer.Layout.HORIZONTAL)
                .addToContainer(uiDevice);
        controlsContainer.setPadding(5);
        controlsContainer.setChildSpacing(5);
        new UIButton(0, 0, 35, 30)
                .setParameter(pattern.flipHorizontal)
                .setLabel("FlipX")
                .addToContainer(controlsContainer);
        new UIButton(40, 0, 35, 30)
                .setParameter(pattern.flipVertical)
                .setLabel("FlipY")
                .addToContainer(controlsContainer);
        new UIKnob(80, 0, 35, 30)
                .setParameter(pattern.tileX)
                .addToContainer(controlsContainer);
        new UIKnob(120, 0, 35, 30)
                .setParameter(pattern.tileY)
                .addToContainer(controlsContainer);
    }
}