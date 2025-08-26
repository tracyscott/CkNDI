
# CkNDI

A Chromatik/LX package that provides NDI (Network Device Interface) video streaming functionality for LED installations. CkNDI receives NDI video streams over the network and maps them to LED fixtures using UV coordinates.

## Features

- **NDI Video Reception**: Receives video streams from NDI sources on the network
- **UV Mapping**: Maps video content to LED fixtures using UV coordinates
- **Real-time Processing**: Handles video frame processing and LED rendering in real-time
- **UI Controls**: Interactive controls for source selection, UV transformations, and visual effects

## Installation

### Prerequisites

- Java 17 or higher
- Maven 3.3.1+
- Chromatik/LX framework

### Building

```bash
# Build the package
mvn package

# Install locally
mvn install

# Install to Chromatik packages directory
mvn -Pinstall install
```

The built JAR will be installed to `~/Chromatik/Packages/` and can be imported through the Chromatik UI.

## Usage

1. **Import Package**: Import the CkNDI package through the Chromatik UI
2. **Add Pattern**: Add the CkNDI pattern to your project
3. **Select NDI Source**: Use the UI controls to select an available NDI source
4. **Configure UV Mapping**: Adjust UV offset, scale, rotation, and other parameters to map video content to your LED installation

### NDI Sources

The pattern automatically discovers NDI sources on your network. Use the refresh button to update the source list, or click the source button to cycle through available sources.

### UV Mapping Controls

- **Offset**: Adjust U/V offset to position the video content
- **Scale**: Control U/V width/height to scale the video content  
- **Rotation**: Rotate the UV coordinates
- **Flip**: Flip video content horizontally or vertically
- **Tile**: Tile the video content across multiple repetitions

## Technical Details

### Dependencies

- **LX Framework** (v1.1.0): Chromatik LED control framework
- **Devolay** (v2.1.0-te): NDI library for video streaming

### Architecture

The pattern consists of three main components:

- **CkNDI**: Main pattern class handling NDI reception and LED rendering
- **UVPoint**: Wrapper for LED points with UV coordinates
- **UVUtil**: Utilities for UV mapping calculations and 3D transformations

## Thanks
Multi-platform build of devolay courtesy of the fine folks over at
[Titanic's End](https://github.com/titanicsend/LXStudio-TE)

## Support

Join us on [Discord &rarr;](https://chromatik.co/discord)

