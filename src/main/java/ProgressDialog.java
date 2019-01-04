import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Dialog;
import java.awt.Dimension;

public abstract class ProgressDialog extends JDialog
{
    private final JLabel msg = new JLabel();
    private final JButton cancelButton;

    public ProgressDialog(String title, JFrame owner)
    {
        super(owner, title,true);
        this.cancelButton = new JButton("Cancel");
        this.cancelButton.addActionListener( ev ->
        {
            try
            {
                System.out.println("Cancelled...");
                cancelPressed();
            } finally {
                dispose();
            }
        });
        getContentPane().setLayout( new BorderLayout() );
        getContentPane().add( msg, BorderLayout.NORTH );
        getContentPane().add( cancelButton , BorderLayout.SOUTH );
        setPreferredSize( new Dimension(240,200) );

        pack();
        setLocationRelativeTo( null );
        setVisible( true );
    }

    protected abstract void cancelPressed();

    public void updateProgress(String message)
    {
        if ( SwingUtilities.isEventDispatchThread() ) {
            msg.setText( message );
        } else {
            SwingUtilities.invokeLater( () -> msg.setText( message ) );
        }
    }
}
