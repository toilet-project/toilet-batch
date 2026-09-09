import com.geupddong.account.LocalMaintenanceLease;
import java.nio.file.Path;

/** Synthetic cross-process test entry point; never targets the production directory. */
public class LocalMaintenanceLeaseProbe {
    public static void main(String[] args) {
        try {
            var path=Path.of(args[0]);
            if (!path.toString().startsWith("/tmp/geupddong-lease-test.")) throw new IllegalArgumentException();
            try(var lease=LocalMaintenanceLease.acquire(path,Integer.parseInt(args[1]))) {
                if(args.length==3 && args[2].equals("reentrant")) {
                    boolean rejected=false;
                    try(var duplicate=LocalMaintenanceLease.acquire(path,Integer.parseInt(args[1]))) { }
                    catch(IllegalStateException expected) {rejected=true;}
                    if(!rejected) throw new IllegalStateException();
                }
                System.out.println("ACQUIRED"); System.out.flush();
                if(args.length==3) System.in.read();
            }
        } catch(Exception ignored) {System.out.println("REJECTED"); System.exit(2);}
    }
}
