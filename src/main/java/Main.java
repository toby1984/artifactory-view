import de.engehausen.treemap.IRectangle;
import de.engehausen.treemap.impl.GenericTreeModel;
import de.engehausen.treemap.swing.TreeMap;

import javax.swing.DefaultListCellRenderer;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JTextField;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Stack;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import java.util.regex.Pattern;

public class Main
{
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern( "yyyy-MM-dd HH:mm:ss.SSSZ" );

    private static final Pattern TZ_PATTERN = Pattern.compile(".*?(\\+|-)\\d{2,}(:)?\\d*$");

    private static final MyTreeMap treeMap = new MyTreeMap();
    private static Optional<ArtifactoryClient.Repository> currentData = Optional.empty();
    private static Predicate<SizeAndLatestDate> nodeFilter = node -> true;
    private static JFrame frame;

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

    public static void main(String[] args) throws Exception
    {
        client.setMaxHostConnections( 10 );
        client.setApiUrl( "http://localhost:8081/artifactory/api" );
        client.setCredentials( "apiuser","apitest" );

        client.connect();

        scanner = new ArtifactoryScanner( client );
        visualize( client.getRepositories() );
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
            System.out.println("Rejected by filter: "+item);
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

    private static void refreshTreemap()
    {
        frame.setTitle( currentData.isPresent() ? currentData.get().repoId : "--" );
        currentData.ifPresent( node ->
        {
            final SizeAndLatestDate nodes = scanner.scanRepo( node );
            treeMap.setTreeModel( createTreeModel( nodes, nodeFilter ) );
        });
    }

    private static void visualize(List<ArtifactoryClient.Repository> repositories)
    {
        currentData = repositories.stream().findFirst();

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
        refreshTreemap();
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
        final JComboBox<ArtifactoryClient.Repository> choices = new JComboBox<>( repositories.toArray( ArtifactoryClient.Repository[]::new ) );
        choices.setRenderer( new DefaultListCellRenderer()
        {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected,
                                                          boolean cellHasFocus)
            {
                final Component result = super.getListCellRendererComponent( list, value, index,
                        isSelected, cellHasFocus );
                final ArtifactoryClient.Repository data = (ArtifactoryClient.Repository) value;
                setText( data.repoId );
                return result;
            }
        });
        currentData.ifPresent( choices::setSelectedItem );
        choices.addActionListener( ev ->
        {
            currentData  = Optional.of( (SizeAndLatestDate) choices.getSelectedItem() );
            refreshTreemap();
        });

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
                    refreshTreemap();
                    return;
                }
                System.err.println("*** Unparseable date: "+text);
            }
            else if ( text == null || text.isBlank() )
            {
                nodeFilter = node -> true;
                refreshTreemap();
            }
        });


        final JButton applyButton = new JButton("Apply");
        applyButton.addActionListener( ev ->
        {
            refreshTreemap();
        });

        // compose panel
        final JPanel panel = new JPanel();
        panel.setLayout(  new GridBagLayout() );

        GridBagConstraints cnstrs = new GridBagConstraints();
        cnstrs.weightx=0.8; cnstrs.weighty = 0;
        cnstrs.fill = GridBagConstraints.HORIZONTAL;
        cnstrs.gridx = 0 ; cnstrs.gridy = 0;
        cnstrs.gridwidth = 1 ; cnstrs.gridheight = 1;
        panel.add( choices, cnstrs);

        cnstrs = new GridBagConstraints();
        cnstrs.weightx=0.2; cnstrs.weighty = 0;
        cnstrs.fill = GridBagConstraints.HORIZONTAL;
        cnstrs.gridx = 1 ; cnstrs.gridy = 0;
        cnstrs.gridwidth = 1 ; cnstrs.gridheight = 1;
        panel.add( lastModified, cnstrs);

        cnstrs = new GridBagConstraints();
        cnstrs.weightx=1; cnstrs.weighty = 1;
        cnstrs.fill = GridBagConstraints.NONE;
        cnstrs.gridx = 2 ; cnstrs.gridy = 0;
        cnstrs.gridwidth = 1; cnstrs.gridheight = 1;
        panel.add( applyButton, cnstrs );

        cnstrs = new GridBagConstraints();
        cnstrs.weightx=1; cnstrs.weighty = 1;
        cnstrs.fill = GridBagConstraints.BOTH;
        cnstrs.gridx = 0 ; cnstrs.gridy = 1;
        cnstrs.gridwidth = 3; cnstrs.gridheight = 1;
        panel.add( treeMap,cnstrs );

        // make frame visible
        frame.getContentPane().add( panel );
        frame.pack();
        frame.setLocationRelativeTo( null );
        frame.setVisible( true );
    }
}