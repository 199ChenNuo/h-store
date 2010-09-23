package tracker;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.Map;
import java.util.HashMap;
import java.math.*;
import java.awt.*;
import java.awt.image.*;
import javax.imageio.*;
import java.io.*;
import javax.swing.*;


public class Board extends JPanel {
        // Add the stuff we need
        static BufferedImage worldmap;
        BufferedImage worldmapbuf;
        BufferedImage satellitepic;
        public int width;
        public int height;
        static double xscale;
        static double yscale;
        static double xorigin;
        static double yorigin;
        static ArrayList<Satellite> sat = new ArrayList<Satellite>();
        static Map<String,Color> colormap = new HashMap<String,Color>();
        static Map<String,BufferedImage> satpics = new HashMap<String,BufferedImage>();
        static int satelliteCount = -1;
        
     public void Board() {

         init_worldmap();
        setPreferredSize(new Dimension(width,height));

    }
    public void paintComponent(Graphics g) {
          super.paintComponent(g);

            // Override the paint command
          if (worldmap == null) { init_worldmap(); }
          g.drawImage(worldmap,  0, 0,null);  
        
          Iterator i = sat.iterator();
            
          while(i.hasNext()) {
             Satellite s = (Satellite) i.next();
             BufferedImage b = satpics.get(s.country);
             g.drawImage(b, 
                     (int) ((s.currentLong * xscale) + xorigin - (b.getWidth()/2)),
                     (int) ((s.currentLat * yscale) + yorigin - (b.getHeight()/2)),
                     null);
                      
          }
    }

                // Initialize all the display elements.
    public void init_worldmap() {
        // construct the board.
        Toolkit tk = Toolkit.getDefaultToolkit();
        
        //Read in the world map
        try { worldmap =  ImageIO.read(new File("worldmap.jpg"));   }
        catch (Exception e) {
            e.printStackTrace();
            System.exit(1);
        }
        
        //Read in the satellite pics
        try { 
            satpics.put("usa", ImageIO.read(new File("us.gif")));
            satpics.put("france", ImageIO.read(new File("fr.gif")));
            satpics.put("brazil", ImageIO.read(new File("br.gif")));
            satpics.put("china", ImageIO.read(new File("cn.gif")));
            satpics.put("india", ImageIO.read(new File("in.gif")));
            
            }
        catch (Exception e) {
            e.printStackTrace();
            System.exit(1);
        }
        
        colormap.put("usa", Color.blue);
        colormap.put("france", Color.yellow);
        colormap.put("brazil", Color.green);
        colormap.put("china", Color.red);
        colormap.put("india", Color.black);
        
        //Set appropriate arguments
        width = worldmap.getWidth();
        height = worldmap.getHeight();
        xorigin = width/2;
        yorigin = height/2;
        xscale = width/Math.toRadians(360.0);
        yscale = height/Math.toRadians(170.0);

    }
    public static void drawmap(double a,double b,double c,double d, String country, String model) {
        final int m,n,o,p;
        m = (int) ((a * xscale) + xorigin);
        n = (int) ((b * yscale) + yorigin);
        o = (int) ((c * xscale) + xorigin);
        p = (int) ((d * yscale) + yorigin);

        Graphics g = worldmap.getGraphics();
        Graphics2D g2 = (Graphics2D) g;
         g2.setStroke(new BasicStroke(2));
         //g2.setColor(Color.yellow);
         g2.setColor(colormap.get(country));

        g2.drawLine(m,n,o,p);
        
    }
    public static void addSat(int id, String model, String country, double x, double y) {
        Satellite s = new Satellite(id, model, country);
        s.id = id;
        s.model = model;
        s.country = country;
        s.currentLat = y;
        s.currentLong = x;
        sat.add(id,s);
        
    }
    public static boolean updateSat(int id, double x, double y) {
            
            try { 
                if (sat.get(id).id != id ) {
                    System.out.println("WHAT!? id " + id + " not the same as the orbit id " + sat.get(id).id);
                }
            } catch (IndexOutOfBoundsException e) {
                return(false);
            }
            double y0 = sat.get(id).currentLat;
            double x0 = sat.get(id).currentLong;
            sat.get(id).currentLat = y;
            sat.get(id).currentLong = x;
            if ( (x > x0) && (Math.abs(y-y0)<.5 ) ) {
                drawmap(x0,y0,x,y,sat.get(id).country,sat.get(id).model);
            }
            return(true);
        }
}
