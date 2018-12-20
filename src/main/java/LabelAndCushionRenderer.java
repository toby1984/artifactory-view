import de.engehausen.treemap.IColorProvider;
import de.engehausen.treemap.ILabelProvider;
import de.engehausen.treemap.IRectangle;
import de.engehausen.treemap.ITreeModel;
import de.engehausen.treemap.swing.impl.CushionRectangleRenderer;
import de.engehausen.treemap.swing.impl.LabelRenderer;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;

final class LabelAndCushionRenderer extends CushionRectangleRenderer<SizeAndLatestDate>
{

    private final LabelRenderer<SizeAndLatestDate> labelrenderer;

    public LabelAndCushionRenderer(Font font, int colorRangeSize)
    {
        super( colorRangeSize );
        this.labelrenderer = new LabelRenderer<>( font );
    }

    public void render(Graphics2D var1, ITreeModel<IRectangle<SizeAndLatestDate>> var2, IRectangle<SizeAndLatestDate> var3, IColorProvider<SizeAndLatestDate, Color> var4, ILabelProvider<SizeAndLatestDate> var5) {
        super.render( var1,var2,var3,var4,var5 );
        labelrenderer.render( var1,var2,var3,var4,var5 );
    }

    public void highlight(Graphics2D var1,
                          ITreeModel<IRectangle<SizeAndLatestDate>> var2,
                          IRectangle<SizeAndLatestDate> var3,
                          IColorProvider<SizeAndLatestDate, Color> var4,
                          ILabelProvider<SizeAndLatestDate> var5)
    {
        super.highlight( var1,var2,var3,var4,var5 );
        labelrenderer.highlight( var1,var2,var3,var4,var5 );
    }
}
