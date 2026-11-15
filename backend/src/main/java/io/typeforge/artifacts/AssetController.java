package io.typeforge.artifacts;

import io.typeforge.identity.IdentityService;
import io.typeforge.shared.DomainException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.Arrays;
import java.util.UUID;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1")
public class AssetController {
    private final AssetService assets;
    private final IdentityService users;
    public AssetController(AssetService assets,IdentityService users) {this.assets=assets;this.users=users;}
    @PostMapping(value="/workspaces/{id}/assets",consumes=MediaType.MULTIPART_FORM_DATA_VALUE) @ResponseStatus(HttpStatus.CREATED)
    public AssetService.Asset upload(Principal principal,@PathVariable UUID id,@RequestPart("file") MultipartFile file,@RequestHeader("Idempotency-Key") String key) throws IOException {
        if(file.getSize()>AssetService.MAX_FILE_BYTES) throw new DomainException("ASSET_TOO_LARGE",413,"Each asset must be at most 8 MiB");
        try(var input=file.getInputStream()) {return assets.upload(owner(principal),id,file.getOriginalFilename(),file.getContentType(),input,key);}
    }
    @GetMapping("/workspaces/{id}/assets") public AssetService.Page list(Principal principal,@PathVariable UUID id,@RequestParam(required=false) UUID cursor,@RequestParam(defaultValue="50") int limit) {return assets.list(owner(principal),id,cursor,limit);}
    @GetMapping("/assets/{id}") public AssetService.Asset get(Principal principal,@PathVariable UUID id) {return assets.get(owner(principal),id);}
    @DeleteMapping("/assets/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(Principal principal,@PathVariable UUID id) {assets.revoke(owner(principal),id);}
    @GetMapping("/assets/{id}/download")
    public ResponseEntity<byte[]> download(Principal principal,@PathVariable UUID id,@RequestHeader(value="Range",required=false) String range,@RequestParam(defaultValue="false") boolean attachment) {
        var download=assets.download(owner(principal),id);byte[] bytes=download.bytes();int start=0,end=bytes.length-1;boolean partial=false;
        var headers=new HttpHeaders();headers.setContentType(MediaType.parseMediaType(download.asset().mimeType()));headers.setCacheControl("private, no-store");
        headers.set("X-Content-Type-Options","nosniff");headers.set("Content-Security-Policy","sandbox; default-src 'none'");headers.set("Accept-Ranges","bytes");
        headers.setETag("\""+download.asset().contentHash()+"\"");headers.setContentDisposition((attachment?ContentDisposition.attachment():ContentDisposition.inline()).filename(download.asset().filename(),StandardCharsets.UTF_8).build());
        if(range!=null) {
            if(!range.matches("bytes=[0-9]*-[0-9]*") || range.equals("bytes=-")) return unsatisfiable(headers,bytes.length);
            String[] positions=range.substring(6).split("-",-1);
            try {
                if(positions[0].isEmpty()) {long suffix=Long.parseLong(positions[1]);if(suffix<=0) return unsatisfiable(headers,bytes.length);start=(int)Math.max(0,bytes.length-suffix);}
                else {long from=Long.parseLong(positions[0]);if(from>=bytes.length) return unsatisfiable(headers,bytes.length);start=(int)from;if(!positions[1].isEmpty()) end=(int)Math.min(bytes.length-1L,Long.parseLong(positions[1]));}
            } catch(NumberFormatException malformed) {return unsatisfiable(headers,bytes.length);}
            if(end<start) return unsatisfiable(headers,bytes.length);partial=true;headers.set("Content-Range","bytes "+start+"-"+end+"/"+bytes.length);
        }
        byte[] body=partial?Arrays.copyOfRange(bytes,start,end+1):bytes;headers.setContentLength(body.length);return new ResponseEntity<>(body,headers,partial?HttpStatus.PARTIAL_CONTENT:HttpStatus.OK);
    }
    private static ResponseEntity<byte[]> unsatisfiable(HttpHeaders headers,int length) {headers.set("Content-Range","bytes */"+length);headers.setContentLength(0);return new ResponseEntity<>(new byte[0],headers,HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE);}
    private UUID owner(Principal principal) {return users.session(principal.getName()).userId();}
}
