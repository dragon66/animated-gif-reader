package com.github.dragon66;

import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileInputStream;

import javax.swing.ImageIcon;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JScrollPane;

public class TestAnimatedGIFReader {

	public static void main(String[] args) throws Exception {
		FileInputStream fin = new FileInputStream(new File(args[0]));
		AnimatedGIFReader reader = new AnimatedGIFReader();
		BufferedImage img = reader.read(fin);		 
		fin.close();
		System.out.println(reader.getFrameCount());
	
		if(img == null)	return;
		 
		final JFrame jframe = new JFrame("AnimatedGIFReader");

		jframe.addWindowListener(new WindowAdapter(){
			 public void windowClosing(WindowEvent evt)
			 {
				 jframe.dispose();
				 System.exit(0);
			 }
		});
		
		 JLabel theLabel = new JLabel(new ImageIcon(reader.getFrame(1)));
		 jframe.getContentPane().add(new JScrollPane(theLabel));
		 jframe.setSize(400,400);
		 jframe.setVisible(true);
	}	
}