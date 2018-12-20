import java.io.Serializable;
import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public final class DataVolume implements Comparable<DataVolume> , Serializable {

	public static final DataVolume ZERO = DataVolume.bytes( 0 );
	
	private final BigDecimal value;
	private volatile BigDecimal sizeInBytes;
	private final Unit unit;
	
	public enum Unit
	{
		BYTES {
			
			private final BigDecimal FACTOR = new BigDecimal(1);
			
			@Override
			public String toString() {
				return "B";
			}			
			
			@Override
			protected BigDecimal getFactor() {
				return 	FACTOR;
			}
		},
		KILOBYTES 
		{
			private final BigDecimal FACTOR = new BigDecimal(1024);
			
			@Override
			public String toString() {
				return "KB";
			}	

			@Override
			protected BigDecimal getFactor() {
				return 	FACTOR;
			}
		},
		MEGABYTES {
			
			private final BigDecimal FACTOR = new BigDecimal(1024*1024);
			
			@Override
			public String toString() {
				return "MB";
			}		
			
			@Override
			protected BigDecimal getFactor() {
				return 	FACTOR;
			}			
		},
		GIGABYTES {
			
			private final BigDecimal FACTOR = new BigDecimal(1024*1024*1024);
			
			@Override
			public String toString() {
				return "GB";
			}
			
			@Override
			protected BigDecimal getFactor() {
				return 	FACTOR;
			}
		},
		TERABYTES {
			private final BigDecimal FACTOR = new BigDecimal(1024*1024*1024).multiply( new BigDecimal(1024 ) );
			
			@Override
			public String toString() {
				return "TB";
			}
			
			@Override
			protected BigDecimal getFactor() {
				return 	FACTOR;
			}
		};		
		
		private static volatile List<Unit> UNITS_FROM_LARGEST_TO_SMALLEST;
		private static volatile List<Unit> UNITS_FROM_SMALLEST_TO_LARGEST;	
		
		public static final Comparator<Unit> COMPARATOR = new Comparator<Unit>() {

			@Override
			public int compare(Unit o1, Unit o2) 
			{
				return o1.getFactor().compareTo(o2.getFactor());
			}
		};
		
		protected abstract BigDecimal getFactor();
		
		public DataVolume convert(DataVolume volume)
		{
			return new DataVolume( volume.getSizeInBytes().divide( getFactor() ) , this );
		}			
		
		public static Unit determineBestDisplayUnit(DataVolume volume)
		{
			final List<Unit> unitsFromSmallestToLargest = getUnitsFromSmallestToLargest();
			for ( int i = 0 ; i < unitsFromSmallestToLargest.size() ; i++)
			{
				final Unit unit  = unitsFromSmallestToLargest.get(i);
				final int cmp = volume.getSizeInBytes().compareTo( unit.getFactor() );
				if ( cmp <= 0 ) {
					if ( ( i -1 ) >= 0 ) {
						return unitsFromSmallestToLargest.get( i-1 );
					}
					return unit;
				}
			}
			return unitsFromSmallestToLargest.get( unitsFromSmallestToLargest.size() -1 );
		}
		
		public static List<Unit> getUnitsFromSmallestToLargest()
		{
			if ( UNITS_FROM_SMALLEST_TO_LARGEST == null ) 
			{
				final List<Unit> units =Arrays.asList( Unit.values() );
				Collections.sort( units , COMPARATOR );
				UNITS_FROM_SMALLEST_TO_LARGEST = Collections.unmodifiableList( units );
			}
			return UNITS_FROM_SMALLEST_TO_LARGEST;
		}		
	}
	
	public static DataVolume bytes(long value) {
		return new DataVolume( new BigDecimal( value ), Unit.BYTES );
	}
	
	public DataVolume(BigDecimal value, Unit unit) {
		if ( unit == null ) {
			throw new IllegalArgumentException("unit cannot be NULL");
		}
		if ( value == null ) {
			throw new IllegalArgumentException("value cannot be NULL");
		}
		if ( value.signum() == -1 ) {
			throw new IllegalArgumentException("value cannot be negative");
		}
		this.value = value;
		this.unit = unit;
	}
	
	public BigDecimal getValue() {
		return value;
	}
	
	public Unit getUnit() {
		return unit;
	}
	
	public DataVolume convertTo(Unit unit)
	{
		if (unit == null) {
			throw new IllegalArgumentException("unit cannot be NULL");
		}
		if ( this.unit == unit ) {
			return this;
		}
		return unit.convert( this );
	}

	public BigDecimal getSizeInBytes()
	{
		if ( this.sizeInBytes == null ) {
			this.sizeInBytes = value.multiply( unit.getFactor() );			
		}
		return sizeInBytes;
	}
	
	@Override
	public String toString() {
		return value+" "+unit;
	}
	
	public String toPrettyString() {
		Unit displayUnit = Unit.determineBestDisplayUnit( this );
		DataVolume converted = convertTo( displayUnit );
		final DecimalFormat DF = new DecimalFormat("###################0.0#");
		return DF.format( converted.getValue() )+" "+displayUnit;
	}
	
	@Override
	public boolean equals(Object obj) {
		if ( obj instanceof DataVolume ) {
			if ( this == obj ) {
				return true;
			}
			return this.getSizeInBytes().equals( ((DataVolume) obj).getSizeInBytes() );
		}
		return false;
	}
	
	@Override
	public int hashCode() {
		return this.getSizeInBytes().hashCode();
	}
	
	@Override
	public int compareTo(DataVolume other)
	{
		BigDecimal val1 = convertTo( Unit.MEGABYTES ).getValue();
		BigDecimal val2 = other.convertTo( Unit.MEGABYTES ).getValue();
		return val1.compareTo( val2 );
	}
	
	public boolean isGreaterThan(DataVolume other) {
		return this.compareTo( other ) > 0;
	}
	
	public boolean isLessThan(DataVolume other) {
		return this.compareTo( other ) < 0;
	}
}