package cn.reviewsystem.practice;

import cn.reviewsystem.api.BusinessException;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import javax.imageio.*;
import javax.imageio.stream.ImageInputStream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.*;
import org.springframework.web.multipart.MultipartFile;

@Service
public class AnswerAssetService {
 static final long MAX_BYTES=5L*1024*1024;private final JdbcTemplate db;private final Path directory;
 public AnswerAssetService(JdbcTemplate db,@Value("${app.answer-assets.directory:${user.home}/.reviewsystem/answer-assets}") String directory){this.db=db;this.directory=Path.of(directory).toAbsolutePath().normalize();}

 @Transactional public PracticeDtos.AssetView upload(long userId,long practiceId,long itemId,MultipartFile part){
  if(part==null||part.isEmpty()||part.getSize()>MAX_BYTES)invalid("Image must be non-empty and no larger than 5 MB");byte[] bytes;try{bytes=part.getBytes();}catch(IOException e){invalid("Unable to read image");return null;}Decoded decoded=decode(bytes);
  var item=db.query("SELECT pi.id,a.status FROM rs_practice_item pi JOIN rs_practice p ON p.id=pi.practice_id JOIN rs_learning_activity a ON a.id=p.activity_id WHERE pi.id=? AND p.id=? AND p.user_id=? FOR UPDATE",(r,n)->new ItemState(r.getLong(1),r.getString(2)),itemId,practiceId,userId);if(item.isEmpty())notFound();if(!item.get(0).status().equals("IN_PROGRESS"))conflict("Images can only be uploaded while the practice is in progress");
  var positions=db.query("SELECT position FROM rs_answer_asset WHERE practice_item_id=? ORDER BY position",(r,n)->r.getInt(1),itemId);if(positions.size()>=3)invalid("A practice item can have at most 3 images");int position=0;while(positions.contains(position))position++;
  String key=UUID.randomUUID().toString().replace("-","")+decoded.extension();Path target=path(key);try{Files.createDirectories(directory);Files.write(target,bytes,StandardOpenOption.CREATE_NEW);}catch(IOException e){throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR,"UPLOAD_INVALID","Unable to store image");}
  try{Long id=db.queryForObject("INSERT INTO rs_answer_asset(user_id,practice_item_id,position,storage_key,mime,size_bytes,sha256) VALUES (?,?,?,?,?,?,?) RETURNING id",Long.class,userId,itemId,position,key,decoded.mime(),bytes.length,sha256(bytes));registerRollback(target);return new PracticeDtos.AssetView(String.valueOf(id),position,decoded.mime(),bytes.length,"/api/practice-assets/"+id);}catch(RuntimeException e){deleteQuietly(target);throw e;}
 }

 public AssetData read(long userId,long id){var rows=db.query("SELECT storage_key,mime FROM rs_answer_asset WHERE id=? AND user_id=?",(r,n)->new String[]{r.getString(1),r.getString(2)},id,userId);if(rows.isEmpty())notFound();try{return new AssetData(Files.readAllBytes(path(rows.get(0)[0])),MediaType.parseMediaType(rows.get(0)[1]));}catch(IOException e){notFound();return null;}}

 @Transactional public void delete(long userId,long practiceId,long itemId,long assetId){var rows=db.query("SELECT aa.storage_key,a.status FROM rs_answer_asset aa JOIN rs_practice_item pi ON pi.id=aa.practice_item_id JOIN rs_practice p ON p.id=pi.practice_id JOIN rs_learning_activity a ON a.id=p.activity_id WHERE aa.id=? AND aa.practice_item_id=? AND pi.practice_id=? AND aa.user_id=? FOR UPDATE",(r,n)->new DeleteTarget(r.getString(1),r.getString(2)),assetId,itemId,practiceId,userId);if(rows.isEmpty())notFound();DeleteTarget target=rows.get(0);if(!target.status().equals("IN_PROGRESS"))conflict("Submitted practice images cannot be deleted");Path file=path(target.storageKey());db.update("DELETE FROM rs_answer_asset WHERE id=?",assetId);try{Files.delete(file);}catch(IOException e){conflict("Practice image file could not be deleted");}}

 private static Decoded decode(byte[] bytes){try(ImageInputStream in=ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))){var readers=ImageIO.getImageReaders(in);if(!readers.hasNext())invalid("File is not a decodable PNG or JPEG image");ImageReader reader=readers.next();try{reader.setInput(in,true,true);String format=reader.getFormatName().toLowerCase();if(!Set.of("png","jpeg","jpg").contains(format))invalid("Only PNG and JPEG are allowed");BufferedImage image=reader.read(0);if(image==null||image.getWidth()<1||image.getHeight()<1)invalid("Image cannot be decoded");return new Decoded(format.equals("png")?"image/png":"image/jpeg",format.equals("png")?".png":".jpg");}finally{reader.dispose();}}catch(IOException e){invalid("Image cannot be decoded");return null;}}
 private static String sha256(byte[] bytes){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
 private Path path(String key){Path value=directory.resolve(key).normalize();if(!value.getParent().equals(directory))notFound();return value;}
 private static void registerRollback(Path target){if(TransactionSynchronizationManager.isSynchronizationActive())TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){@Override public void afterCompletion(int status){if(status!=STATUS_COMMITTED)deleteQuietly(target);}});}
 private static void deleteQuietly(Path path){try{Files.deleteIfExists(path);}catch(IOException ignored){}}
 private static void invalid(String message){throw new BusinessException(HttpStatus.BAD_REQUEST,"UPLOAD_INVALID",message);}private static void conflict(String message){throw new BusinessException(HttpStatus.CONFLICT,"STATE_CONFLICT",message);}private static void notFound(){throw new BusinessException(HttpStatus.NOT_FOUND,"NOT_FOUND","Practice image not found");}
 record Decoded(String mime,String extension){}record ItemState(long id,String status){}record DeleteTarget(String storageKey,String status){}public record AssetData(byte[] bytes,MediaType mediaType){}
}
