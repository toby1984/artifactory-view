import de.engehausen.treemap.IRectangle;
import de.engehausen.treemap.impl.GenericTreeModel;
import de.engehausen.treemap.swing.TreeMap;

import javax.swing.DefaultComboBoxModel;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Stack;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.regex.Pattern;

public class Main
{
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern( "yyyy-MM-dd HH:mm:ss.SSSZ" );

    private static final Pattern TZ_PATTERN = Pattern.compile(".*?(\\+|-)\\d{2,}(:)?\\d*$");

    private static final MyTreeMap treeMap = new MyTreeMap();
    private static Optional<ArtifactoryClient.Repository> currentRepo = Optional.empty();
    private static Predicate<SizeAndLatestDate> nodeFilter = node -> true;
    private static JFrame frame;
    private static final JComboBox<ArtifactoryClient.Repository> choices = new JComboBox<>();

    private static final AtomicBoolean stopWorker = new AtomicBoolean();
    private static Thread currentWorker = null;

    private static final JTextField apiUrl = new JTextField("http://localhost:8081/artifactory/api");
    private static final JTextField apiUser = new JTextField("apiuser");
    private static final JTextField apiPassword = new JTextField("apitest");

    private static final DateTimeFormatter[] DATE_FORMATS =
            {
                    DateTimeFormatter.ofPattern( "yyyy-MM-dd", Locale.getDefault() ),
                    DateTimeFormatter.ofPattern( "yyyy-MM-dd HH:mm" ),
                    DateTimeFormatter.ofPattern( "yyyy-MM-dd HH:mmZ" ),
                    DateTimeFormatter.ofPattern( "yyyy-MM-dd HH:mm:ss" ),
                    DateTimeFormatter.ofPattern( "yyyy-MM-dd HH:mm:ssZ" ),
                    DateTimeFormatter.ofPattern( "yyyy-MM-dd HH:mm:ss.SSSZ" ),
            };

    private static final ArtifactoryClient client = new ArtifactoryClient();
    private static ArtifactoryScanner scanner;

    private static boolean fetchRepos = true;

    public static void main(String[] args) throws Exception
    {
        client.setMaxHostConnections( 10 );
        scanner = new ArtifactoryScanner( client );
        init();
    }

    private static GenericTreeModel<SizeAndLatestDate> createTreeModel(SizeAndLatestDate root, Predicate<SizeAndLatestDate> pred)
    {
        final AtomicInteger accepted = new AtomicInteger();
        final AtomicInteger rejected = new AtomicInteger();
        final Predicate<SizeAndLatestDate> filter = item ->
        {
            if ( pred.test(item) ) {
                accepted.incrementAndGet();
                return true;
            }
            rejected.incrementAndGet();
            return false;
        };

        final GenericTreeModel<SizeAndLatestDate> model = new GenericTreeModel<>();
        final SizeAndLatestDate filtered = root.copy( null,filter );
        if ( filtered != null )
        {
            filtered.fixSizes();
            filtered.sortChildren();

            final Stack<SizeAndLatestDate> stack = new Stack<>();
            stack.add( root );
            while ( !stack.isEmpty() )
            {
                SizeAndLatestDate node = stack.pop();
                model.add( node, node.sizeInBytes, node.parent, false );
                stack.addAll( node.children );
            }
        }
        System.err.println( (accepted.get()+rejected.get())+" nodes, "+rejected.get()+" rejected, "+accepted.get()+" accepted");
        return model;
    }

    private static final class MyTreeMap extends TreeMap<SizeAndLatestDate> {

        @Override
        public IRectangle<SizeAndLatestDate> findRectangle(int x, int y)
        {
            return super.findRectangle( x, y );
        }
    }

    private static void refreshTreemap(Optional<ArtifactoryClient.Repository> repository)
    {
        if ( repository.isPresent() )
        {
            while ( currentWorker != null && currentWorker.isAlive() )
            {
                stopWorker.set(true);
                try {
                    Thread.sleep(300);
                }
                catch (InterruptedException e)
                {
                    e.printStackTrace();
                }
            }

            stopWorker.set(false);

            final Thread newWorker = new Thread( ()->
            {
                final AtomicReference<ProgressDialog> dialogRef = new AtomicReference();
                try
                {
                    runOnEDT( () ->
                    {
                        final String msg = "Scanning "+repository.get().repoId+" ...";
                        final ProgressDialog dialog = new ProgressDialog(msg,frame)
                        {
                            @Override
                            protected void cancelPressed()
                            {
                                stopWorker.set(true);
                            }
                        };
                        dialogRef.set(dialog);
                    },true);
                    final ArtifactoryScanner.IProgressReporter reporter =
                            new ArtifactoryScanner.IProgressReporter()
                            {
                                private final AtomicInteger count = new AtomicInteger();

                                @Override
                                public void itemScanned()
                                {
                                    final int cnt = count.incrementAndGet();
                                    if ( ( cnt % 100 ) == 0 )
                                    {
                                        SwingUtilities.invokeLater( () ->
                                        {
                                            final ProgressDialog progressDialog = dialogRef.get();
                                            if ( progressDialog != null )
                                            {
                                                progressDialog.updateProgress( "Scanned: " + cnt + " items" );
                                            }
                                        });
                                    }
                                }
                            };

                    final SizeAndLatestDate nodes = scanner.scanRepo( repository.get(), stopWorker::get, reporter );
                    runOnEDT( () ->
                    {
                        frame.setTitle( repository.get().repoId );
                        treeMap.setTreeModel( createTreeModel( nodes, nodeFilter ) );
                    });
                }
                catch (InterruptedException e)
                {
                    // ok, we got interrupted
                }
                finally
                {
                    runOnEDT( () ->
                    {
                        final ProgressDialog dialog = dialogRef.get();
                        if ( dialog != null )
                        {
                            dialog.dispose();
                        }
                    } );
                }

            },"worker");
            newWorker.setDaemon( true );
            newWorker.start();
            currentWorker = newWorker;
        } else {
            frame.setTitle( "--" );
        }
        currentRepo = repository;
    }

    public static void runOnEDT(Runnable r) {
        runOnEDT(r,false);
    }

    public static void runOnEDT(Runnable r,boolean later)
    {
        if ( SwingUtilities.isEventDispatchThread() ) {
            r.run();
        }
        else
        {
            try
            {
                if ( later ) {
                    SwingUtilities.invokeLater( r );
                }
                else {
                    SwingUtilities.invokeAndWait( r );
                }
            }
            catch (Exception e)
            {
                e.printStackTrace();
            }
        }
    }

    private static void fetchRepos()
    {
        if ( fetchRepos )
        {
            System.out.println( "Fetching repositories..." );
            final List<ArtifactoryClient.Repository> repos = client.getRepositories();
            System.out.println( "Got " + repos.size() + " repositories" );
            choices.setModel( new DefaultComboBoxModel<>( repos.toArray( ArtifactoryClient.Repository[]::new ) ) );
            if ( ! repos.isEmpty() ) {
                choices.setSelectedItem( repos.get(0) );
            }
            fetchRepos = false;
        }
    }

    private static void init()
    {
        currentRepo = Optional.empty();

        // setup frame
        frame = new JFrame() ;
        frame.setDefaultCloseOperation( JFrame.EXIT_ON_CLOSE );

        // setup tree map
        treeMap.setLabelProvider( (iTreeModel, iRectangle) ->
        {
            final SizeAndLatestDate node = iRectangle.getNode();
            return node.getPath() + " ("+node.size().toPrettyString() + ")";
        });
        final LabelAndCushionRenderer treeRenderer = new LabelAndCushionRenderer( frame.getFont(), 64 );
        treeMap.setRectangleRenderer( treeRenderer );
        treeMap.setPreferredSize(  new Dimension(640,480) );
        treeMap.setSize(  new Dimension(640,480) );
        treeMap.addMouseMotionListener( new MouseAdapter()
        {
            @Override
            public void mouseMoved(MouseEvent e)
            {
                final IRectangle<SizeAndLatestDate> rect = treeMap.findRectangle( e.getX(), e.getY() );
                if ( rect != null ) {
                    final SizeAndLatestDate node = rect.getNode();
                    final String tt = "<html>Path: "+ node.getPath() + "<br>" +
                            "Size: " + node.size().toPrettyString() + "<br>" +
                            "Last updated: "+ (node.latestDate==null?"--": DATE_FORMAT.format(node.latestDate ) )+"</html>";

                    treeMap.setToolTipText( tt );
                } else {
                    treeMap.setToolTipText( null );
                }
            }
        });

        // choices dropdown
        choices.setRenderer( new DefaultListCellRenderer()
        {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected,
                                                          boolean cellHasFocus)
            {
                final Component result = super.getListCellRendererComponent( list, value, index,
                        isSelected, cellHasFocus );
                final ArtifactoryClient.Repository data = (ArtifactoryClient.Repository) value;
                if ( data != null )
                {
                    setText( data.repoId );
                }
                return result;
            }
        });
        currentRepo.ifPresent( choices::setSelectedItem );

        // 'last modified' restriction
        final JTextField lastModified = new JTextField(15);

        lastModified.addActionListener(  ev ->
        {
            String text = lastModified.getText();
            if ( text != null && ! text.isBlank() )
            {
                if ( ! TZ_PATTERN.matcher( text ).matches() ) {
                    text += DateTimeFormatter.ofPattern( "Z" ).format( ZonedDateTime.now() );
                }
                for ( DateTimeFormatter format : DATE_FORMATS )
                {
                    final ZonedDateTime date;
                    try
                    {
                        date = ZonedDateTime.parse( text, format );
                    }
                    catch(Exception e)
                    {
                        System.err.println( e.getMessage() );
                        continue;
                    }
                    nodeFilter = node -> node.latestDate == null || ! node.isLeaf() || node.latestDate.isBefore( date );
                    return;
                }
                System.err.println("*** Unparseable date: "+text);
            }
            else if ( text == null || text.isBlank() )
            {
                nodeFilter = node -> true;
            }
        });


        final JButton applyButton = new JButton("Apply");
        applyButton.addActionListener( ev ->
        {
            client.setApiUrl( apiUrl.getText() );
            client.setCredentials( apiUser.getText(), apiPassword.getText() );
            fetchRepos();
            final Optional<ArtifactoryClient.Repository> selected = Optional.ofNullable( (ArtifactoryClient.Repository) choices.getSelectedItem() );
            refreshTreemap(selected);
        });

        // API URL & credentials
        final JPanel buttonPanel = new JPanel();
        buttonPanel.setLayout( new FlowLayout() );
        buttonPanel.add( new JLabel("URL"));
        buttonPanel.add( apiUrl );
        buttonPanel.add( new JLabel("User"));
        buttonPanel.add( apiUser );
        buttonPanel.add( new JLabel("Password"));
        buttonPanel.add( apiPassword );

        final JPanel panel = new JPanel();
        panel.setLayout(  new GridBagLayout() );

        GridBagConstraints cnstrs = new GridBagConstraints();
        cnstrs.weightx=1; cnstrs.weighty = 0;
        cnstrs.fill = GridBagConstraints.HORIZONTAL;
        cnstrs.gridx = 0 ; cnstrs.gridy = 0;
        cnstrs.gridwidth = 3 ; cnstrs.gridheight = 1;
        panel.add( buttonPanel, cnstrs);

        cnstrs = new GridBagConstraints();
        cnstrs.weightx=0.8; cnstrs.weighty = 0;
        cnstrs.fill = GridBagConstraints.HORIZONTAL;
        cnstrs.gridx = 0 ; cnstrs.gridy = 1;
        cnstrs.gridwidth = 1 ; cnstrs.gridheight = 1;
        panel.add( choices, cnstrs);

        cnstrs = new GridBagConstraints();
        cnstrs.weightx=0.2; cnstrs.weighty = 0;
        cnstrs.fill = GridBagConstraints.HORIZONTAL;
        cnstrs.gridx = 1 ; cnstrs.gridy = 1;
        cnstrs.gridwidth = 1 ; cnstrs.gridheight = 1;
        panel.add( lastModified, cnstrs);

        cnstrs = new GridBagConstraints();
        cnstrs.weightx=1; cnstrs.weighty = 1;
        cnstrs.fill = GridBagConstraints.NONE;
        cnstrs.gridx = 2 ; cnstrs.gridy = 1;
        cnstrs.gridwidth = 1; cnstrs.gridheight = 1;
        panel.add( applyButton, cnstrs );

        cnstrs = new GridBagConstraints();
        cnstrs.weightx=1; cnstrs.weighty = 1;
        cnstrs.fill = GridBagConstraints.BOTH;
        cnstrs.gridx = 0 ; cnstrs.gridy = 2;
        cnstrs.gridwidth = 3; cnstrs.gridheight = 1;
        panel.add( treeMap,cnstrs );

        // make frame visible
        frame.getContentPane().add( panel );
        frame.pack();
        frame.setLocationRelativeTo( null );
        frame.setVisible( true );
    }
}