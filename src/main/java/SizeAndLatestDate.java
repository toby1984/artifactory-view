import org.apache.commons.lang3.Validate;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

public final class SizeAndLatestDate
{
    public final SizeAndLatestDate parent;
    public final List<SizeAndLatestDate> children = new ArrayList<>();
    private final ArtifactoryClient.Repository repo;
    private final String path;
    public long sizeInBytes;
    public ZonedDateTime latestDate;

    public SizeAndLatestDate(SizeAndLatestDate parent, String path)
    {
        Validate.notNull( parent, "parent must not be null" );
        Validate.notNull( path, "path must not be null" );
        this.repo = null;
        this.parent = parent;
        this.path = path;
        parent.children.add(this);
    }

    public SizeAndLatestDate(ArtifactoryClient.Repository repo, String path)
    {
        Validate.notNull( path, "path must not be null or blank");
        Validate.notNull( repo, "repo must not be null" );
        this.parent = null;
        this.repo = repo;
        this.path = path;
    }

    public boolean isLeaf() {
        return children.isEmpty();
    }

    public DataVolume size() {
        return DataVolume.bytes( this.sizeInBytes );
    }

    private SizeAndLatestDate copy(SizeAndLatestDate newParent)
    {
        final SizeAndLatestDate result = newParent != null ? new SizeAndLatestDate(newParent,this.path) : new SizeAndLatestDate( repo,getPath());
        result.sizeInBytes = this.sizeInBytes;
        result.latestDate = this.latestDate;
        return result;
    }

    public SizeAndLatestDate copy(SizeAndLatestDate parent, Predicate<SizeAndLatestDate> filter) {

        if ( ! filter.test(  this ) ) {
            return null;
        }
        final SizeAndLatestDate result = copy(parent);
        this.children.stream().filter( filter ).map( x -> x.copy(result,filter) ).forEach( result.children::add );
        return result;
    }

    public void fixSizes()
    {
        if ( ! children.isEmpty() )
        {
            children.forEach( SizeAndLatestDate::fixSizes );

            // update size
            long newSize = children.stream().mapToLong( x -> x.sizeInBytes ).sum();
            if ( newSize != sizeInBytes )
            {
                this.sizeInBytes = newSize;
            }
        }
    }

    @Override
    public boolean equals(Object obj)
    {
        if( obj instanceof SizeAndLatestDate )
        {
            final SizeAndLatestDate other = (SizeAndLatestDate) obj;
            return getRepoId().equals( other.getRepoId() ) && getPath().equals( other.getPath() );
        }
        return false;
    }

    @Override
    public int hashCode()
    {
        return 31*(31*getRepoId().hashCode()+getPath().hashCode());
    }

    public String getRepoId() {
        return parent == null ? repo.repoId : parent.getRepoId();
    }

    public String getPath()
    {
        if ( parent == null ) {
            return path;
        }
        return parent.getPath()+path;
    }

    public synchronized void merge(long size,ZonedDateTime date)
    {
        this.sizeInBytes += size;
        if ( latestDate == null || date.isAfter( latestDate ) )
        {
            latestDate = date;
        }
    }

    public synchronized void merge(SizeAndLatestDate other)
    {
        merge(other.sizeInBytes,other.latestDate);
    }

    @Override
    public synchronized String toString()
    {
        return getRepoId()+";"+getPath()+";"+sizeInBytes+";"+latestDate;
    }

    public void sortChildren()
    {
        children.forEach( x -> x.sortChildren() );
        children.sort( (a,b) -> a.getPath().compareToIgnoreCase( b.getPath() ) );
    }
}
