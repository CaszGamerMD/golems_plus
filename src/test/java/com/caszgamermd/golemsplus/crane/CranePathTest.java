package com.caszgamermd.golemsplus.crane;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
final class CranePathTest {
    @Test void routesAroundWallInsteadOfCrossingIt(){
        var start=new CranePath.Cell(0,0,0);var goal=new CranePath.Cell(3,0,0);
        var path=CranePath.find(start,goal::equals,p->p.distance(goal),
            p->p.y()==0&&Math.abs(p.z())<=2&&p.x()>=0&&p.x()<=3&&!(p.x()==1&&p.z()==0),(a,b)->true,100);
        assertNotNull(path);assertEquals(goal,path.getLast());assertFalse(path.contains(new CranePath.Cell(1,0,0)));
    }
    @Test void railsCanClimbButCannotJumpMissingSegments(){
        var start=new CranePath.Cell(0,0,0);var goal=new CranePath.Cell(0,5,0);
        assertNotNull(CranePath.find(start,goal::equals,p->p.distance(goal),p->p.x()==0&&p.z()==0&&p.y()>=0&&p.y()<=5,(a,b)->true,20));
        assertNull(CranePath.find(start,goal::equals,p->p.distance(goal),p->p.x()==0&&p.z()==0&&p.y()>=0&&p.y()<=5&&p.y()!=3,(a,b)->true,20));
    }
    @Test void blockedSweptEdgeAndBudgetFailClosed(){
        var start=new CranePath.Cell(0,0,0);var goal=new CranePath.Cell(1,0,0);
        assertNull(CranePath.find(start,goal::equals,p->p.distance(goal),p->true,(a,b)->false,20));
        assertNull(CranePath.find(start,goal::equals,p->p.distance(goal),p->true,(a,b)->true,0));
    }
}
