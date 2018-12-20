import org.apache.commons.lang3.Validate;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class ArtifactoryScanner
{
    private final ExecutorService pool;
    private final ArtifactoryClient client;

    public ArtifactoryScanner(ArtifactoryClient client) throws Exception
    {
        Validate.notNull( client, "client must not be null" );
        this.client = client;
        final LinkedBlockingQueue<Runnable> workQueue =
                new LinkedBlockingQueue<>();

        final ThreadFactory tf = new ThreadFactory()
        {
            private final AtomicInteger ID = new AtomicInteger();
            @Override
            public Thread newThread(Runnable r)
            {
                final Thread t = new Thread(r);
                t.setDaemon( true );
                t.setName("thread-"+ID.incrementAndGet());
                return t;
            }
        };
        pool = new ThreadPoolExecutor( 1000, 1000, 60, TimeUnit.SECONDS, workQueue, tf, new ThreadPoolExecutor.CallerRunsPolicy() );
    }

    public SizeAndLatestDate scanRepo(ArtifactoryClient.Repository repo)
    {
        return scanRepo(new SizeAndLatestDate( repo,""), "", 0);
    }

    private SizeAndLatestDate scanRepo(SizeAndLatestDate parent,String pathComponent,int depth)
    {
        // get folders
        final String repoId = parent.getRepoId();
        final SizeAndLatestDate size = "".equals( pathComponent ) ? parent : new SizeAndLatestDate( parent , pathComponent );
        final List<ArtifactoryClient.Item> children = client.getChildren( repoId, size.getPath() );

        final AtomicInteger count = new AtomicInteger();
        final Object LOCK = new Object();

        for ( var child : children )
        {
            final String uri = child.path;
            if ( child.isFolder() )
            {
                if ( depth < 5 )
                {
                    count.incrementAndGet();
                    final Runnable r = () ->
                    {
                        try
                        {
                            size.merge( scanRepo( size, uri, depth + 1 ) );
                        }
                        finally {
                            if ( count.decrementAndGet() == 0 ) {
                                synchronized(LOCK) {
                                    LOCK.notifyAll();
                                }
                            }
                        }
                    };
                    pool.submit( r );
                }
                else
                {
                    size.merge( scanRepo( size, uri, depth + 1 ) );
                }
            }
            else
            {
                // not a folder
                size.merge( child.sizeInBytes, child.lastUpdated );
            }
        }
        while ( count.get() > 0 )
        {
            synchronized(LOCK)
            {
                try
                {
                    LOCK.wait( 100 );
                }
                catch (InterruptedException e) { /* nop */ }
            }
        }
        return size;
    }
}