package com.gathin.tvhome;

import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

/** Draws the tvOS style lighter band behind each carrete row and adds a gap between rows. */
public class DockDecoration extends RecyclerView.ItemDecoration {

    private final int dockSize;
    private final float density;
    private final Paint band = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    /** Extra band below the tiles for their names (always_show_names), in px. */
    private final float namesRoom;
    /** Light glass like the top bar's pills (config.xml dock_glass): a white tint that fades downwards. */
    private final boolean lightGlass;
    /** Padding all round the tiles equal to the gap between them (config.xml dock_even_padding). */
    private final boolean evenPadding;
    /** Draw the band and its edge (config.xml dock_band); false leaves the tiles floating on the wallpaper. */
    private final boolean bandVisible;
    /** single_row: the tiles after the dock share its row, so they take the same offsets to stay level with it. */
    private final boolean singleRow;
    /** dock_band_all: in a single row, the band runs behind every app instead of the dock apps only. */
    private final boolean bandAll;

    DockDecoration(int dockSize, float density, boolean translucent, boolean names, int opacity, boolean lightGlass,
                   boolean evenPadding, boolean bandVisible, boolean singleRow, boolean bandAll) {
        this.dockSize = Math.max(1, dockSize);
        this.singleRow = singleRow;
        this.bandAll = singleRow && bandAll;
        this.density = density;
        this.namesRoom = names ? 19 * density : 0;
        this.lightGlass = lightGlass;
        this.evenPadding = evenPadding;
        this.bandVisible = bandVisible;
        band.setColor(translucent ? 0x8C121014 : (Math.round(opacity * 2.55f) << 24) | 0x141416);
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(density);
        edge.setColor(lightGlass ? 0x38FFFFFF : translucent ? 0x26FFFFFF : 0x1FFFFFFF);
    }

    /** Concentric corners: the band's radius is the tiles' radius plus the band's padding around them. */
    static float radiusPx(float tileRadiusPx, float paddingPx) {
        return tileRadiusPx + paddingPx;
    }

    /**
     * The band's padding on every side for dock_even_padding: the gap between two dock tiles (a cell minus its tile, as
     * AppAdapter.tileWidthPx lays them out), capped by the room the grid leaves at its edges.
     */
    static float evenPaddingPx(int innerWidthPx, int columns, int tileWidthPx, float roomPx) {
        float gap = columns > 0 ? innerWidthPx / (float) columns - tileWidthPx : 0f;
        return Math.max(0f, Math.min(gap, roomPx));
    }

    @Override
    public void getItemOffsets(@NonNull Rect outRect, @NonNull View view, @NonNull RecyclerView parent,
                               @NonNull RecyclerView.State state) {
        int pos = parent.getChildAdapterPosition(view);
        if (pos >= 0) {
            outRect.top = (int) (5 * density);
            outRect.bottom = (int) (8 * density);
        }
    }

    @Override
    public void onDraw(@NonNull Canvas c, @NonNull RecyclerView parent, @NonNull RecyclerView.State state) {
        if (!bandVisible) return;
        int maxRow = -1;
        for (int i = 0; i < parent.getChildCount(); i++) {
            int pos = parent.getChildAdapterPosition(parent.getChildAt(i));
            if (pos >= 0) {
                int r = singleRow ? 0 : pos / dockSize;
                if (r > maxRow) maxRow = r;
            }
        }
        for (int targetRow = 0; targetRow <= maxRow; targetRow++) {
            boolean any = false;
            int cardWidth = 0;
            for (int i = 0; i < parent.getChildCount(); i++) {
                View child = parent.getChildAt(i);
                int pos = parent.getChildAdapterPosition(child);
                if (pos < 0) continue;
                if (parent.getAdapter() instanceof AppAdapter && ((AppAdapter) parent.getAdapter()).isLibraryAt(pos)) continue;
                int row = singleRow ? 0 : pos / dockSize;
                if (row != targetRow) continue;
                RecyclerView.ViewHolder vh = parent.getChildViewHolder(child);
                View card = vh instanceof AppAdapter.Holder ? ((AppAdapter.Holder) vh).card : child.findViewById(R.id.card);
                if (card == null) continue;
                float l = child.getLeft() + card.getLeft();
                float t = child.getTop() + card.getTop() + child.getTranslationY();
                float r = l + card.getWidth();
                float b = t + card.getHeight();
                cardWidth = card.getWidth();
                if (!any) {
                    rect.set(l, t, r, b);
                    any = true;
                } else {
                    rect.union(l, t, r, b);
                }
            }
            if (!any) continue;
            float pad = 10 * density;
            float sidePad = 14 * density;
            float side = Math.max(0f, Math.min(sidePad, Math.min(rect.left, parent.getWidth() - rect.right) - density));
            float radius = AppAdapter.cornerRadius(cardWidth, density);
            if (evenPadding) {
                int inner = parent.getWidth() - parent.getPaddingLeft() - parent.getPaddingRight();
                float room = (singleRow ? rect.top : Math.min(rect.top, Math.min(rect.left, parent.getWidth() - rect.right))) - density;
                float even = evenPaddingPx(inner, dockSize, cardWidth, room);
                rect.inset(-even, -even);
                radius = radiusPx(radius, even);
            } else {
                rect.inset(singleRow ? -sidePad : -side, -pad);
                radius = 22 * density;
            }
            if (singleRow) {
                rect.left = density;
                rect.right = parent.getWidth() - density;
            }
            rect.bottom += namesRoom;
            if (lightGlass) {
                band.setShader(new LinearGradient(0, rect.top, 0, rect.bottom, 0x24FFFFFF, 0x0DFFFFFF,
                        Shader.TileMode.CLAMP));
            }
            c.drawRoundRect(rect, radius, radius, band);
            c.drawRoundRect(rect, radius, radius, edge);
        }
    }
}
