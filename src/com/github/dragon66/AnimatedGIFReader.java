/**
 * Copyright (c) 2014-2015 by Wen Yu.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * Any modifications to this file must keep this entire header intact.
 * 
 * Change History - most recent changes go on top of previous changes
 *
 * AnimatedGIFReader.java
 *
 * Who   Date       Description
 * ====  =========  =================================================
 * WY    20Nov2015  Initial creation
 */

package com.github.dragon66;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.awt.image.ColorModel;
import java.awt.image.DataBuffer;
import java.awt.image.DataBufferByte;
import java.awt.image.IndexColorModel;
import java.awt.image.Raster;
import java.awt.image.WritableRaster;
import java.io.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 
 * Decodes and shows images in GIF format, supports both Gif87a and Gif89a.
 * The current class doesn't support interlaced or animated GIFs,but can 
 * anyway shows these kinds of images! Supports transparent GIFs!
 *
 * Change log: the LZW decoding part becomes a general purpose class which
 * could be used to decode TIFF image as well.
 */
public class AnimatedGIFReader {
	// Global fields
	private GifHeader gifHeader;
	private int logicalScreenWidth;
	private int logicalScreenHeight;
	private Color backgroundColor = new Color(255, 255, 255);
	private int[] globalColorPalette;
	// Graphic control extension specific fields
	protected int disposalMethod = GIFFrame.DISPOSAL_UNSPECIFIED;
	protected int userInputFlag = GIFFrame.USER_INPUT_NONE;
	protected int transparencyFlag = GIFFrame.TRANSPARENCY_INDEX_NONE;
	protected int transparent_color = GIFFrame.TRANSPARENCY_COLOR_NONE;
	protected int delay;
	// Frame specific fields
	private int colorsUsed;
	protected int image_x;
	protected int image_y;
	private int width;
	private int height;
	private int bitsPerPixel;
	private int rgbColorPalette[];

	// To keep track of all the frames
	private List<GIFFrame> gifFrames;
	private List<BufferedImage> frames;
	
	// BufferedImage with the width and height of the logical screen to draw frames upon
	private BufferedImage baseImage;
	
	private byte[] decodeLZW(InputStream is) throws Exception {
		int dimension = width*height;		
		byte[] temp_ = new byte[dimension];

		int min_code_size = is.read();// The length of the root
		LZWTreeDecoder decoder = new LZWTreeDecoder(is, min_code_size);
		decoder.decode(temp_, 0, dimension);
		
		return temp_;
	}
   
	private byte[] decodeLZWInterLaced(InputStream is) throws Exception	{
		int index = 0;
		int index2 = 0;
		int passParam[] = {0,8,4,8,2,4,1,2};
		int passStart[] = {0,width*passParam[2],width*passParam[4],width*passParam[6]};
		int passInc[]   = {width*passParam[1],width*passParam[3],width*passParam[5],width*passParam[7]};
		int passHeight[]= {((height-1)>>3)+1,((height+3)>>3),((height+1)>>2),((height)>>1)}; 

		/////////////////////////////////////
		int min_code_size = is.read();// The length of the root

		int dimension = width*height;
		byte[] buf = new byte[dimension];
		byte[] temp_ = new byte[dimension];

		LZWTreeDecoder decoder = new LZWTreeDecoder(is, min_code_size);
		decoder.decode(buf, 0, dimension);
   
		for (int pass=1;pass<5;pass++)
		{
			// pass 1: start at row 0, scan every 8 rows
			// pass 2: start at row 4, scan every 8 rows
			// pass 3: start at row 2, scan every 4 rows
			// pass 4: start at row 1, scan every 2 rows
			index = passStart[pass-1];
			int inc = (passInc[pass-1]-width);
			for(int row=0;row<passHeight[pass-1];row++,index+=inc)
			{
				for(int col=0;col<width;col++,index++,index2++)
				{
					temp_[index] = buf[index2];
				}
			}
		}
	
		return temp_;
	}
   
	public Color getBackgroundColor() {
		return backgroundColor;
	}
   
	/**
	 * Gets the current frame as a BufferedImage. The frames created this may assume
	 * different sizes and are intended to be located at different positions in the
	 * case of an animated GIF. Therefore, the frames may only occupy part of the
	 * logical screen and may also rely on transparency and previous frames to work properly.
	 * <p>
	 * Note: do not mix this method with {@link #read(InputStream) read} 
	 *       or {@link #getFrameAsBufferedImageEx(InputStream) getFrameAsBufferedImageEx}.
	 *       Use them separately.
	 * <p> One way to use this method to retrieve all the frames from an animated GIF:
	 * <pre>
	 * {@code
	 * GIFReader reader = new GIFReader();
	 * InputStream is = new FileInputStream(new File(pathToImage));
	 * List<BufferedImage> frames = new ArrayList<BufferedImage>();
	 * BufferedImage bi = null; 
	 * while((bi = reader.getFrameAsBufferedImage(is) != null)
	 * 	frames.add(bi);
	 * }
	 * </pre>
	 * 
	 * @param is InputStream for the GIF/Animated GIF
	 * @return a BufferedImage for the image or current frame in case of animated GIF
	 * @throws Exception
	 */
	protected BufferedImage getFrameAsBufferedImage(InputStream is) throws Exception {
		// Read frame into a byte array
		byte[] pixels = readFrame(is);
		if(pixels == null) return null;
		//Create a BufferedImage
		int[] off = {0};//band offset, we have only one band start at 0
		DataBuffer db = new DataBufferByte(pixels, pixels.length);
		WritableRaster raster = Raster.createInterleavedRaster(db, width, height, width, 1, off, null);
		ColorModel cm = new IndexColorModel(bitsPerPixel, rgbColorPalette.length, rgbColorPalette, 0, false, transparent_color, DataBuffer.TYPE_BYTE);
   	
		return new BufferedImage(cm, raster, false, null);
	}
	
	/**
	 * Gets the current frame as a BufferedImage the same size as the logical screen.
	 * Graphic Control Extension and Image Descriptor parameters are taken into account
	 * When creating the frame. The resulting frame is actually a "composite" one or a
	 * snapshot as seen in an animated GIF. Such frames may not be the same as the frames
	 * created by {@link #getFrameAsBufferedImage(InputStream) getFrameAsBufferedImage}
	 * which could be of different sizes and may also have to rely on the previous frames
	 * to look the same as the frames created here.
	 *  
	 * <p>
	 * Note: do not mix this method with {@link #read(InputStream) read} 
	 *       or {@link #getFrameAsBufferedImage(InputStream) getFrameAsBufferedImage}.
	 *       Use them separately.
	 *  
	 * @param is input stream for the image - single frame or multiple frame animated GIF
	 * @return java BufferedImage or null if there is no more frames
	 * @throws Exception
	 */
	protected BufferedImage getFrameAsBufferedImageEx(InputStream is) throws Exception {
		// This single call will trigger the reading of the global scope data
		BufferedImage bi = getFrameAsBufferedImage(is);
		if(bi == null) return null;
		int maxWidth = (width < logicalScreenWidth)? width:logicalScreenWidth;
		int maxHeight = (height < logicalScreenHeight)? height:logicalScreenHeight;
		if(baseImage == null)
			baseImage = new BufferedImage(logicalScreenWidth, logicalScreenHeight, BufferedImage.TYPE_INT_ARGB);
		Rectangle area = new Rectangle(image_x, image_y, maxWidth, maxHeight);
		// Create a backup bufferedImage from the base image for the area of the current frame
		BufferedImage backup = new BufferedImage(maxWidth, maxHeight, BufferedImage.TYPE_INT_ARGB);
		backup.setData(baseImage.getData(area));
		/* End of backup */
		Graphics2D g = baseImage.createGraphics();
		// Draw this frame to the base
		g.drawImage(bi, image_x, image_y, null);
		// We need to clone the base image since we are going to dispose it later according to the disposal method
		BufferedImage clone = new BufferedImage(logicalScreenWidth, logicalScreenHeight, BufferedImage.TYPE_INT_ARGB);
		clone.setData(baseImage.getData());
		// Check about disposal method to take action accordingly
		if(disposalMethod == 1 || disposalMethod == 0) // Leave in place or unspecified
			; // No action needed
		else if(disposalMethod == 2) { // Restore to background
			Composite oldComposite = g.getComposite();
			g.setComposite(AlphaComposite.Clear);
			g.fillRect(image_x, image_y, width, height);
			g.setComposite(oldComposite);
		} else if(disposalMethod == 3) { // Restore to previous
			Composite oldComposite = g.getComposite();
			g.setComposite(AlphaComposite.Src);
			g.drawImage(backup, image_x, image_y, null);
			g.setComposite(oldComposite);
		} else { // To be defined - should never come here
			baseImage = new BufferedImage(logicalScreenWidth, logicalScreenHeight, BufferedImage.TYPE_INT_ARGB);
			g = baseImage.createGraphics();
		}	
		
		return clone;
	}
	
	/**
	 * Get the total number of frames read by this GIFReader.
	 *  
	 * @return number of frames read by this GIFReader or 0 if not read yet
	 */
	public int getFrameCount() {
		if(frames != null) // We have already read the image
			return frames.size();
		return 0; // We haven't read the image yet
	}
	
	public BufferedImage getFrame(int i) {
		if(frames == null) return null;
		if(i < 0 || i >= frames.size())
			throw new IndexOutOfBoundsException("Index: " + i);
		return frames.get(i);
	}
	
	/**
	 * Get the total frames read by this GIFRreader.
	 * 
	 * @return a list of the total frames read by this GIFRreader or empty list if not read yet
	 */
	public List<BufferedImage> getFrames() {
		if(frames != null)
			return Collections.unmodifiableList(frames);
		return Collections.emptyList();
	}
	
	public GIFFrame getGIFFrame(int i) {
		if(gifFrames == null) return null;
		if(i < 0 || i >= gifFrames.size())
			throw new IndexOutOfBoundsException("Index: " + i);
		return gifFrames.get(i);
	}
	
	public List<GIFFrame> getGIFFrames() {
		if(gifFrames != null)
			return Collections.unmodifiableList(gifFrames);
		return Collections.emptyList();			
	}

	public int getLogicalScreenHeight() {
		return logicalScreenHeight;
	}
    
	public int getLogicalScreenWidth() {
		return logicalScreenWidth;
	}
	
	public int getTransparentColor() {
		if(transparent_color >= 0)
			return rgbColorPalette[transparent_color]&0xffffff; // We only need RGB, no alpha
		return GIFFrame.TRANSPARENCY_COLOR_NONE;
	}
	
	public boolean isTransparent() {
		return transparencyFlag == GIFFrame.TRANSPARENCY_INDEX_SET;
	}
   
	private byte[] readFrame(InputStream is) throws Exception {
		// One time read of global scope data
		if(gifHeader == null) {
			if(!readGlobalScopeData(is)) return null;
		}
		
		resetFrameParameters();
	   
		int image_separator = 0;
	
		do {		   
			image_separator = is.read();
			    
			if(image_separator == -1 || image_separator == 0x3b) { // End of stream 
				return null;
			}
			    
			if (image_separator == 0x21) // (!) Extension Block
			{
				int func = is.read();
				int len = is.read();
	
				if (func == 0xf9) { // Graphic Control Label - identifies the current block as a Graphic Control Extension
					int packedFields = is.read();
					// Determine the disposal method
					disposalMethod = ((packedFields&0x1c)>>2);
					userInputFlag =  ((packedFields&0x02)>>1);
					delay = IOUtils.readUnsignedShort(is);
					// Read transparent color index
					int transparent_color_index = is.read();
					// Check for transparent color flag
					if((packedFields&0x01) == 0x01){
						transparencyFlag = GIFFrame.TRANSPARENCY_INDEX_SET;
						transparent_color = transparent_color_index;
					}					
					len = is.read();// len=0, block terminator!					
				}
				// GIF87a specification mentions the repetition of multiple length
				// blocks while GIF89a gives no specific description. For safety, here
				// a while loop is used to check for block terminator!
				while(len != 0) {
					IOUtils.skipFully(is, len);
					len = is.read();// len=0, block terminator!
				} 
			}
		} while(image_separator != 0x2c); // ","
	
		byte flags2 = readImageDescriptor(is);
		   
		boolean hasLocalColorMap = false;
	
		if((flags2&0x80) == 0x80) {
			hasLocalColorMap = true;
			// A local color map is present
			bitsPerPixel = (flags2&0x07)+1;
			colorsUsed = (1<<bitsPerPixel);
	
			readLocalPalette(is, colorsUsed);
		}
		   
		if(!hasLocalColorMap) rgbColorPalette = globalColorPalette;
		   
		if (transparencyFlag == GIFFrame.TRANSPARENCY_INDEX_SET && transparent_color < colorsUsed)
			rgbColorPalette[transparent_color] &= 0x00ffffff;
			
		if((flags2&0x40) == 0x40) {
			return decodeLZWInterLaced(is);
		}
		
		return decodeLZW(is);
	}
    
	private void readGlobalPalette(InputStream is,int num_of_color) throws Exception {
		int index1 = 0;
		int bytes2read = num_of_color*3;
		byte brgb[] = new byte[bytes2read];  
		IOUtils.readFully(is,brgb,0,bytes2read);
	
		globalColorPalette = new int[num_of_color];
				
		for(int i = 0; i < num_of_color; i++)
			globalColorPalette[i]  = ((255<<24)|((brgb[index1++]&0xff)<<16)|((brgb[index1++]&0xff)<<8)|(brgb[index1++]&0xff));
	}
    
	private boolean readGlobalScopeData(InputStream is) throws Exception {
		// Global scope data including header, logical screen descriptor, global colorPalette if presents
		gifHeader = new GifHeader();
		gifHeader.readHeader(is);
		   
		logicalScreenWidth = gifHeader.screen_width;
		logicalScreenHeight = gifHeader.screen_height;
	
		String signature = new String(gifHeader.signature) + new String(gifHeader.version);
			
		if ((!signature.equalsIgnoreCase("GIF87a")) && (!signature.equalsIgnoreCase("GIF89a")))	{
			return false;
		}
	      
		byte flags = gifHeader.flags;
					
		if((flags&0x80) == 0x80) { // A global color map is present 
			bitsPerPixel = (flags&0x07)+1;
			colorsUsed = (1<<bitsPerPixel);
	
			// # bits of color resolution, insignificant 
			@SuppressWarnings("unused")
			int bitsPerColor = ((flags&0x70)>>4)+1;
	
			readGlobalPalette(is, colorsUsed);
			int bgcolor = gifHeader.bgcolor&0xff;
			if(bgcolor < colorsUsed)
			   backgroundColor = new Color(globalColorPalette[bgcolor]);
	   	}
		   
	   	return true;
	}
    
	public BufferedImage read(InputStream is) throws Exception {
		frames = new ArrayList<BufferedImage>();
		gifFrames = new ArrayList<GIFFrame>();
		BufferedImage bi = null;
		
		while((bi = getFrameAsBufferedImageEx(is)) != null) {
			gifFrames.add(new GIFFrame(bi, image_x, image_y, delay, disposalMethod, userInputFlag, transparencyFlag, transparent_color));
			frames.add(bi);			
		}
		
		return frames.get(0);
	}
    
	private byte readImageDescriptor(InputStream is) throws Exception {	 	
		int nindex = 0;
		byte ides[] = new byte[9];
	
		IOUtils.readFully(is,ides,0,9);
	
		image_x = (ides[nindex++]&0xff)|((ides[nindex++]&0xff)<<8);
		image_y = (ides[nindex++]&0xff)|((ides[nindex++]&0xff)<<8);
		width =  (ides[nindex++]&0xff)|((ides[nindex++]&0xff)<<8);
		height = (ides[nindex++]&0xff)|((ides[nindex++]&0xff)<<8);

		return ides[nindex++];
	}
    
	private void readLocalPalette(InputStream is,int num_of_color) throws Exception	{
		int index1 = 0;
		int bytes2read = num_of_color*3;
		byte brgb[] = new byte[bytes2read];  
		IOUtils.readFully(is,brgb,0,bytes2read);
	
		rgbColorPalette = new int[num_of_color];
			
		for(int i = 0; i < num_of_color; i++)
			rgbColorPalette[i] = ((255<<24)|((brgb[index1++]&0xff)<<16)|((brgb[index1++]&0xff)<<8)|(brgb[index1++]&0xff));
	}
	
	private void resetFrameParameters() {
		// Need to reset some of the fields
		disposalMethod = GIFFrame.DISPOSAL_UNSPECIFIED;
		userInputFlag = GIFFrame.USER_INPUT_NONE;
		transparencyFlag = GIFFrame.TRANSPARENCY_INDEX_NONE;
		transparent_color = GIFFrame.TRANSPARENCY_COLOR_NONE;
		delay = 0;
		image_x = 0;
		image_y = 0;
		width = 0;
		height = 0;
		// End of fields reset
	}
	
	private static class GifHeader {
		private byte  signature[] = new byte[3];
		private byte  version[] = new byte[3];

		private int screen_width;
		private int screen_height;
		private byte  flags;
		private byte  bgcolor;
		@SuppressWarnings("unused")
		private byte  aspectRatio;
  
		void readHeader(InputStream is) throws Exception {
			int nindex = 0;
			byte bhdr[] = new byte[13];

			IOUtils.readFully(is,bhdr,0,13);
	
			for(int i = 0; i < 3; i++)
				signature[i] = bhdr[nindex++];
	      
			for(int i = 0; i < 3; i++)
				version[i] = bhdr[nindex++];
	      
			screen_width = ((bhdr[nindex++]&0xff)|((bhdr[nindex++]&0xff)<<8));
			screen_height = ((bhdr[nindex++]&0xff)|((bhdr[nindex++]&0xff)<<8));
			flags = bhdr[nindex++];
			bgcolor = bhdr[nindex++];
			aspectRatio = bhdr[nindex++];
			// The end
		}
	}
  
	public static class GIFFrame {
		// Frame parameters
		private BufferedImage frame;
		private int leftPosition;
		private int topPosition;
		private int frameWidth;
		private int frameHeight;
		private int delay;
		private int disposalMethod = DISPOSAL_UNSPECIFIED;
		private int userInputFlag = USER_INPUT_NONE;
		private int transparencyFlag = TRANSPARENCY_INDEX_NONE;
		
		// The transparent color value in RRGGBB format.
		// The highest order byte has no effect.
		private int transparentColor = TRANSPARENCY_COLOR_NONE; // Default no transparent color
		
		public static final int DISPOSAL_UNSPECIFIED = 0;
		public static final int DISPOSAL_LEAVE_AS_IS = 1;
		public static final int DISPOSAL_RESTORE_TO_BACKGROUND = 2;
		public static final int DISPOSAL_RESTORE_TO_PREVIOUS = 3;
		// Values between 4-7 inclusive
		public static final int DISPOSAL_TO_BE_DEFINED = 7;
		
		public static final int USER_INPUT_NONE = 0;
		public static final int USER_INPUT_EXPECTED = 1;
		
		public static final int TRANSPARENCY_INDEX_NONE = 0;
		public static final int TRANSPARENCY_INDEX_SET = 1;
		
		public static final int TRANSPARENCY_COLOR_NONE = -1;
		
		public GIFFrame(BufferedImage frame) {
			this(frame, 0, 0, 0, GIFFrame.DISPOSAL_UNSPECIFIED);
		}
		
		public GIFFrame(BufferedImage frame, int delay) {
			this(frame, 0, 0, delay, GIFFrame.DISPOSAL_UNSPECIFIED);
		}
		
		public GIFFrame(BufferedImage frame, int delay, int disposalMethod) {
			this(frame, 0, 0, delay, disposalMethod);
		}
		
		public GIFFrame(BufferedImage frame, int leftPosition, int topPosition, int delay, int disposalMethod) {
			this(frame, leftPosition, topPosition, delay, disposalMethod, USER_INPUT_NONE, TRANSPARENCY_INDEX_NONE, TRANSPARENCY_COLOR_NONE);
		}
		
		public GIFFrame(BufferedImage frame, int leftPosition, int topPosition, int delay, int disposalMethod, int userInputFlag, int transparencyFlag, int transparentColor) {
			if(frame == null) throw new IllegalArgumentException("Null input image");
			if(disposalMethod < DISPOSAL_UNSPECIFIED || disposalMethod > DISPOSAL_TO_BE_DEFINED)
				throw new IllegalArgumentException("Invalid disposal method: " + disposalMethod);
			if(userInputFlag < USER_INPUT_NONE || userInputFlag > USER_INPUT_EXPECTED)
				throw new IllegalArgumentException("Invalid user input flag: " + userInputFlag);
			if(transparencyFlag < TRANSPARENCY_INDEX_NONE || transparencyFlag > TRANSPARENCY_INDEX_SET)
				throw new IllegalArgumentException("Invalid transparency flag: " + transparencyFlag);
			if(leftPosition < 0 || topPosition < 0)
				throw new IllegalArgumentException("Negative coordinates for frame top-left position");
			if(delay < 0) delay = 0;
			this.frame = frame;
			this.leftPosition = leftPosition;
			this.topPosition = topPosition;	
			this.delay = delay;
			this.disposalMethod = disposalMethod;
			this.userInputFlag = userInputFlag;
			this.transparencyFlag = transparencyFlag;
			this.frameWidth = frame.getWidth();
			this.frameHeight = frame.getHeight();
			this.transparentColor = transparentColor;
		}
		
		public int getDelay() {
			return delay;
		}
		
		public int getDisposalMethod() {
			return disposalMethod;
		}
		
		public BufferedImage getFrame() {
			return frame;
		}
		
		public int getFrameHeight() {
			return frameHeight;
		}
		
		public int getFrameWidth() {
			return frameWidth;
		}
		
		public int getLeftPosition() {
			return leftPosition;
		}
		
		public int getTopPosition() {
			return topPosition;
		}
		
		public int getTransparentColor() {
			return transparentColor;
		}
		
		public int getTransparencyFlag() {
			return transparencyFlag;
		}
		
		public int getUserInputFlag() {
			return userInputFlag;
		}
	}
	
	private static class IOUtils {
			 
		public static void readFully(InputStream is, byte b[]) throws IOException {
			readFully(is, b, 0, b.length);
		}
		 
		public static void readFully(InputStream is, byte[] b, int off, int len) throws IOException {
			if (len < 0)
				throw new IndexOutOfBoundsException();
			int n = 0;         
			while (n < len) {
				int count = is.read(b, off + n, len - n);
				if (count < 0)
					throw new EOFException();
				n += count;
			}
		}
		 
		public static int readUnsignedShort(InputStream is) throws IOException {
			byte[] buf = new byte[2];
			readFully(is, buf);
			
			return ((buf[1]&0xff)<<8)|(buf[0]&0xff);
		}
		
		public static void skipFully(InputStream is, int n) throws IOException {
			readFully(is, new byte[n]);
		}	
		 
		private IOUtils() {}
	}
	 
	private static class LZWTreeDecoder {
	
		// Variables for code reading
		private int bits_remain = 0;
		private int bytes_available = 0;
		private int temp_byte = 0;        
		private int bufIndex = 0;
		private byte bytes_buf[] = new byte[256];
	    
		private int oldcode = 0 ;
		private int code = 0;
		private int[] prefix = new int[4097];
		private int[] suffix = new int[4097];

		private int min_code_size;
		private int clearCode;
		// End of image for GIF or end of information for TIFF
		private int endOfImage;

		// Variables to clear table
		private int codeLen;
		private int codeIndex;
		private int limit;

		private int first_code_index;
		private int first_char;

		private InputStream is;
	
		private static final int MASK[] = {0x00,0x001,0x003,0x007,0x00f,0x01f,0x03f,0x07f,0x0ff,0x1ff,0x3ff,0x7ff,0xfff};
		
	    private int leftOver = 0;// Used to keep track of the not fully expanded code string.
		private int buf[] = new int[4097];
		
		private static final int MAX_CODE = (1<<12);
		
		/**
		 * There are some subtle differences between the LZW algorithm used by TIFF and GIF images.
		 *
		 * Variable Length Codes:
		 * Both TIFF and GIF use a variation of the LZW algorithm that uses variable length codes.
		 * In both cases, the maximum code size is 12 bits. The initial code size, however, is different
		 * between the two formats. TIFF's initial code size is always 9 bits. GIF's initial code size 
		 * is specified on a per-file basis at the beginning of the image descriptor block, 
		 * with a minimum of 3 bits.
		 * <p>
		 * TIFF and GIF each switch to the next code size using slightly different algorithms. 
		 * GIF increments the code size as soon as the LZW string table's length is equal to 2**code-size,
		 * while TIFF increments the code size when the table's length is equal to 2**code-size - 1.
		 * <p>
		 * Packing Bits into Bytes
		 * TIFF and GIF LZW algorithms differ in how they pack the code bits into the byte stream.
		 * The least significant bit in a TIFF code is stored in the most significant bit of the bytestream,
		 * while the least significant bit in a GIF code is stored in the least significant bit of the bytestream.
		 * <p>
		 * Special Codes
		 * TIFF and GIF both add the concept of a 'Clear Code' and a 'End of Information Code' to the LZW algorithm. 
		 * In both cases, the 'Clear Code' is equal to 2**(code-size - 1) and the 'End of Information Code' is equal
		 * to the Clear Code + 1. These 2 codes are reserved in the string table. So in both cases, the LZW string
		 * table is initialized to have a length equal to the End of Information Code + 1.	
		 */
		public LZWTreeDecoder(InputStream is, int min_code_size) {
			if(min_code_size < 2 || min_code_size > 12)
				   throw new IllegalArgumentException("invalid min_code_size: " + min_code_size);
			this.is = is;
		   	this.min_code_size = min_code_size;
		   	clearCode = (1<<min_code_size);
		   	endOfImage = clearCode+1;
		   	first_code_index = endOfImage+1;
		   	// Reset string table
		   	clearStringTable();
		}
		
		private void clearStringTable() {
		   	// Reset string table
		   	codeLen = min_code_size+1;
		   	limit = (1<<codeLen)-1;
		   	codeIndex = endOfImage;	
		}
		
		public int decode(byte[] pix, int offset, int len) throws Exception {
			int counter = 0;// Keep track of how many bytes have been decoded.
			///////////////
			int tempcode = 0;
			int i = 0;
	        //////////////////////////////////////////////////////////
			if(leftOver>0){//flush out left over first.
				for( int j = leftOver-1; j >= 0; j--, leftOver-- ) {
					   if ((offset >= pix.length)||(counter>=len))// Will this ever happen?!
						   return counter;
					   pix[offset++] = (byte)buf[j];
					   counter++;
		       }
			}
	        //////////////////////////////////////////////////////////
	        label:
			do {
				i = 0;
				code = readLZWCode();
				tempcode = code;

				if(code == clearCode) {
					clearStringTable();
				} else if(code == endOfImage) {  
				    break;
				} else {
				   if(code >= codeIndex) {
	                    tempcode = oldcode;
	  				    buf[i++] = first_char;
				   }
			       while (tempcode >= first_code_index) {
				       buf[i++] = suffix[tempcode];
			           tempcode = prefix[tempcode];
			       }
			       buf[i++] = tempcode;

				   suffix[codeIndex] = first_char = tempcode;
			       prefix[codeIndex] = oldcode;
			       // Check boundary to deal with deferred clear code in LZW compression
			       if(codeIndex < MAX_CODE) codeIndex++; 
			       
			       oldcode = code;
		           
				   if((codeIndex > limit) && (codeLen<12)) {
			           codeLen++;
				       limit = (1<<codeLen)-1;			  
				   }
				   // Output strings for the current code
			       leftOver = i;
				   for( int j = i-1; j >= 0; j--, leftOver--, counter++ ) {
					   if ((offset >= pix.length)||(counter>=len))
				             break label;
					   pix[offset++] = (byte)buf[j];
			       }
			    }
	        } while(true);

			return counter;
	 	}
	   
		private int readLZWCode() throws Exception {
	        int temp = 0;		
			temp = (temp_byte >> (8-bits_remain));
		
			while (codeLen > bits_remain) {
				if(bytes_available == 0) {
					// find another data block available
					// Start a new image data sub-block if possible!
	            	// The block size bytes_available is no bigger than 0xff
					bytes_available = is.read();
					
					if(bytes_available > 0) {
						IOUtils.readFully(is,bytes_buf,0,bytes_available);
						bufIndex = 0;
					} else if(bytes_available == 0)
						return endOfImage;
					else {
						return endOfImage;
					}
				}				
				temp_byte = bytes_buf[bufIndex++]&0xff;
				bytes_available--;
				temp |= (temp_byte<<bits_remain);			
				bits_remain += 8;
			}
			
			bits_remain -= codeLen;
	        
			return (temp&MASK[codeLen]);
		}		
	}
}