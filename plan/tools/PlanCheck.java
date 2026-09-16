import org.yaml.snakeyaml.Yaml;
import java.nio.file.*; import java.util.*;
public class PlanCheck {
  public static void main(String[] a) throws Exception {
    Path root = Paths.get(a[0]); Yaml yaml = new Yaml(); int errors = 0;
    Map<String,Object> plan;
    try (var in = Files.newInputStream(root.resolve("plan.yaml"))) { plan = yaml.load(in); }
    Map<String,List<String>> deps = new LinkedHashMap<>(); Map<String,String> files = new LinkedHashMap<>();
    for (Object p : (List<Object>) plan.get("phases")) { Map<String,Object> ph=(Map<String,Object>)p;
      for (Object s : (List<Object>) ph.get("steps")) { Map<String,Object> st=(Map<String,Object>)s;
        String id=(String)st.get("id"); files.put(id,(String)st.get("file"));
        List<String> d=(List<String>)st.get("depends_on"); deps.put(id,d==null?List.of():d);
        if(!ph.get("id").equals(id.split("-")[0])) { System.out.println("ERR phase mismatch "+id); errors++; }
      } }
    String[] required={"id","title","phase","status","depends_on","estimate","owner","summary","requirements","tdd","verification","acceptance_criteria","docs_to_update","risks","rollback"};
    for (var e : files.entrySet()) { Path f=root.resolve(e.getValue());
      if(!Files.exists(f)){System.out.println("ERR missing file "+f); errors++; continue;}
      Map<String,Object> st; try (var in=Files.newInputStream(f)) { st=yaml.load(in);} catch(Exception ex){System.out.println("ERR yaml "+f+": "+ex.getMessage().split("\n")[0]); errors++; continue;}
      if(!e.getKey().equals(st.get("id"))){System.out.println("ERR id mismatch "+f); errors++;}
      for(String k:required) if(!st.containsKey(k)){System.out.println("ERR "+f+" missing key "+k); errors++;}
      List<String> d=(List<String>)st.get("depends_on"); if(d!=null && !d.equals(deps.get(e.getKey()))){System.out.println("WARN depends_on differs from plan.yaml in "+f+" "+d+" vs "+deps.get(e.getKey()));}
      for(String dep: deps.get(e.getKey())) if(!files.containsKey(dep)){System.out.println("ERR unknown dep "+dep+" in "+e.getKey()); errors++;}
    }
    // cycle check
    Map<String,Integer> state=new HashMap<>();
    for(String id:deps.keySet()) if(dfs(id,deps,state)){System.out.println("ERR cycle at "+id); errors++;}
    // findings: every finding step exists
    for(Object o:(List<Object>)plan.get("review_findings_index")){Map<String,Object> f=(Map<String,Object>)o; for(String s:((String)f.get("step")).split(",")){ if(!files.containsKey(s.trim())){System.out.println("ERR finding "+f.get("id")+" names unknown step "+s); errors++;}}}
    // runnable
    System.out.println("steps="+files.size()+" errors="+errors);
    List<String> runnable=new ArrayList<>(); for(var e:deps.entrySet()) if(e.getValue().isEmpty()) runnable.add(e.getKey());
    System.out.println("runnable now: "+runnable);
    System.exit(errors==0?0:1);
  }
  static boolean dfs(String n, Map<String,List<String>> g, Map<String,Integer> st){ Integer s=st.get(n); if(s!=null) return s==1; st.put(n,1); for(String m:g.getOrDefault(n,List.of())) if(dfs(m,g,st)) return true; st.put(n,2); return false; }
}
