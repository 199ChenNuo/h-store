package tracker;
import java.awt.*;
import java.io.IOException;

import javax.swing.*;

import org.voltdb.*;
import org.voltdb.client.ClientFactory;

import procedures.*;

public class UserInterface {

    static JFrame frame;
    static JLabel label;
    static Board board;
    final static org.voltdb.client.Client db = ClientFactory.createClient();
 
    // Create a constructor method, where the UI is constructed.
    public static void main(String args[]){
        initUI();
        /*
         * Initialize the database.
         */
    init_db();
    
        
       // Periodically update tracker.
        while (true) {
            if (!fetchData()) {
                System.out.println("No data found in database...");
            }
            pause(2);
        }
    }
    
    private static boolean fetchData() {
        // We don't need anything special for this.}
        // Let's do some pretend actions...
        int id;
        String model, country;
        double x, y;
        int rowCount = 0;
        VoltTable[] locationresult = null;
        
            //First fetch all the location data
        try {
            locationresult = db.callProcedure(GetLocationData.class.getSimpleName() );
            //assert locationresult.length == 1;
            rowCount = locationresult[0].getRowCount();

        } 
        catch (java.io.IOException e) {
            e.printStackTrace();
        }
        catch (org.voltdb.client.ProcCallException e) {
            System.out.println("Get Location data failed: " + e.getMessage());
            return(false);
       }
        
            // Now read through it row by row and update our local copy and map.
        if (rowCount == 0) { return(false); }
        
        for (int ii = 0; ii < rowCount; ii++) {  
            VoltTableRow row = locationresult[0].fetchRow(ii);
            id = (int) row.getLong(0);
            y = row.getDouble(1);
            x = row.getDouble(2);
            
                //  Add the data to our local map. If the update fails, it is a new satellite
                // and we have to add it to the list first.
             if (!board.updateSat(id,x,y)) {
                System.out.printf("Need to create Satellite %d\n",id);
                   int satrowCount = 0;
                   VoltTable[] satelliteresult = null;
                try {
                    satelliteresult = db.callProcedure(GetSatelliteData.class.getSimpleName(),id );
                    assert satelliteresult.length == 1;
                    satrowCount = satelliteresult[0].getRowCount();

                } 
                catch (java.io.IOException e) {
                    e.printStackTrace();
                }
                catch (org.voltdb.client.ProcCallException e) {
                    System.out.println("Get Satellite data failed: " + e.getMessage());
                }
                if (satrowCount == 0) {
                    System.out.println("What? No satellite data to match location data...");
                    model="unknown";
                    country="unknown";
                } else {
                    
                final VoltTableRow satdata= satelliteresult[0].fetchRow(0);
                    model = satdata.getString(0);
                    country = satdata.getString(1);
                }
                 
                board.addSat(id,model, country, x, y);
            } 
         }

                //Now update the display
            frame.update(frame.getGraphics());
 
    return(true);

    }
    private static void initUI() {

           frame = new JFrame("VoltDB Demo");
           label = new JLabel("Satellite Tracking Demo",JLabel.CENTER);
           board = new Board();
           label.setPreferredSize(new Dimension(600, 50));
           board.init_worldmap();
           board.setPreferredSize(new Dimension(board.width,board.height));
           label.setPreferredSize(new Dimension(board.width, 50));
           label.setHorizontalTextPosition(JLabel.CENTER);
           label.setVerticalTextPosition(JLabel.CENTER);
           Container cpane = frame.getContentPane();
           cpane.setLayout(new BorderLayout(0,1));

           cpane.add(label,BorderLayout.NORTH);
           cpane.add(board,BorderLayout.SOUTH);
 
            //Display the window.
            frame.pack();
            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            frame.setVisible(true);
    }
    
    private static void pause(double secs) {
        try { 
            Thread.sleep((long) (secs*1000) );
        }
        catch (Exception e) {
            System.out.print(e.getMessage());
            System.exit(1);
        }

    }
 

static void init_db() {
    boolean started = false;
    while (!started) {
        try {
            db.createConnection("localhost", "tracker", "vonbraun");
            started = true;
        }
        catch (java.net.ConnectException e) {
            System.out.print("Server not running yet. Pausing before Trying again...\n");
        }
        catch (IOException e) {
            e.printStackTrace();
            System.exit(-1);
        }
        if (!started) {
            pause(10.0);
        }
    }

}
}

