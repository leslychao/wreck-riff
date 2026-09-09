package game.wreckriff.ui;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ProgressNoticeTest {
    @Test void asyncFailureAnnouncesOnceAndRemainsVisibleUntilConfirmedSaved() {
        var presenter=new ProgressNotice();
        assertEquals("",presenter.update("",true,true,10).indicator());
        assertEquals("Saving...",presenter.update("",true,true,10.5).indicator());
        var failed=presenter.update("Cannot replace stats.json",true,true,11);
        assertEquals("Progress not saved",failed.indicator());assertFalse(failed.announcement().isEmpty());
        assertEquals("Cannot replace stats.json",failed.detailToLog());
        var repeated=presenter.update("Cannot replace stats.json",true,true,20);
        assertEquals("",repeated.announcement());assertEquals("Progress not saved",repeated.indicator());
        var saved=presenter.update("",false,true,21);
        assertEquals("Progress saved.",saved.announcement());assertEquals("",saved.indicator());
    }
    @Test void normalFastWriteDoesNotFlashAndFutureSchemaIsShownAsReadOnly() {
        var presenter=new ProgressNotice();
        presenter.update("",true,true,0);
        var fast=presenter.update("",false,true,.1);assertEquals("",fast.announcement());assertEquals("",fast.indicator());
        var future=presenter.update("Future schema is preserved",false,false,1);
        assertEquals("Progress is read-only",future.indicator());assertEquals("Future schema is preserved",future.detailToLog());
    }
}
