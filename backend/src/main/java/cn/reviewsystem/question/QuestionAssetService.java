package cn.reviewsystem.question;

import cn.reviewsystem.api.BusinessException;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.HexFormat;
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
public class QuestionAssetService {
 static final long MAX_BYTES=5L*1024*1024;static final int MAX_IMAGES=5;
 private final JdbcTemplate db;private final Path directory;
 public QuestionAssetService(JdbcTemplate db,@Value("${app.question-assets.directory:${user.home}/.reviewsystem/question-assets}") String directory){this.db=db;this.directory=Path.of(directory).toAbsolutePath().normalize();}
 public AdminQuestionDetail.AssetView upload(long questionId,MultipartFile part){
  if(part==null||part.isEmpty()||part.getSize()>MAX_BYTES)invalid("Image must be non-empty and no larger than 5 MB");
  byte[] bytes;try{bytes=part.getBytes();}catch(IOException e){invalid("Unable to read image");return null;}
  var version=db.query("SELECT v.id FROM rs_question q JOIN rs_question_version v ON v.id=q.current_version_id WHERE q.id=? AND q.subject_id=1",(r,n)->r.getLong(1),questionId);if(version.isEmpty())throw new BusinessException(HttpStatus.NOT_FOUND,"NOT_FOUND","Question not found");
  return storeFromBytes(version.get(0),bytes);
 }
 public void validateImageBytes(byte[] bytes,String expectedSha256){
  if(bytes==null||bytes.length==0||bytes.length>MAX_BYTES)invalid("Image must be non-empty and no larger than 5 MB");
  decode(bytes);
  if(expectedSha256==null||!expectedSha256.trim().matches("[0-9a-fA-F]{64}"))invalid("Image sha256 must be a 64-character hex string");
  String actual=sha256(bytes);
  if(!actual.equalsIgnoreCase(expectedSha256.trim()))invalid("Image sha256 mismatch: expected "+expectedSha256.trim()+", actual "+actual);
 }
 @Transactional public AdminQuestionDetail.AssetView storeFromBytes(long versionId,byte[] bytes){
  if(bytes==null||bytes.length==0||bytes.length>MAX_BYTES)invalid("Image must be non-empty and no larger than 5 MB");
  Decoded decoded=decode(bytes);
  Integer count=db.queryForObject("SELECT count(*) FROM rs_question_asset WHERE question_version_id=?",Integer.class,versionId);if(count!=null&&count>=MAX_IMAGES)invalid("A question version can have at most 5 images");
  String key=java.util.UUID.randomUUID().toString().replace("-","")+decoded.extension();Path target=directory.resolve(key).normalize();if(!target.getParent().equals(directory))invalid("Invalid storage key");
  try{Files.createDirectories(directory);Files.write(target,bytes,StandardOpenOption.CREATE_NEW);}catch(IOException e){throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR,"UPLOAD_INVALID","Unable to store image");}
  if(TransactionSynchronizationManager.isSynchronizationActive())TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){@Override public void afterCompletion(int status){if(status!=STATUS_COMMITTED)deleteQuietly(target);}});
  try{String sha=sha256(bytes);int position=count==null?0:count;Long id=db.queryForObject("INSERT INTO rs_question_asset(question_version_id,position,storage_key,mime,size_bytes,width,height,sha256) VALUES (?,?,?,?,?,?,?,?) RETURNING id",Long.class,versionId,position,key,decoded.mime(),bytes.length,decoded.width(),decoded.height(),sha);return new AdminQuestionDetail.AssetView(String.valueOf(id),position,decoded.mime(),bytes.length,decoded.width(),decoded.height(),sha);}catch(RuntimeException e){deleteQuietly(target);throw e;}
 }
 public AssetData read(long id){var rows=db.query("SELECT storage_key,mime FROM rs_question_asset WHERE id=?",(r,n)->new String[]{r.getString(1),r.getString(2)},id);if(rows.isEmpty())throw new BusinessException(HttpStatus.NOT_FOUND,"NOT_FOUND","Image not found");Path path=directory.resolve(rows.get(0)[0]).normalize();if(!path.getParent().equals(directory))throw new BusinessException(HttpStatus.NOT_FOUND,"NOT_FOUND","Image not found");try{return new AssetData(Files.readAllBytes(path),MediaType.parseMediaType(rows.get(0)[1]));}catch(IOException e){throw new BusinessException(HttpStatus.NOT_FOUND,"NOT_FOUND","Image not found");}}
 @Transactional public void copyVersionAssets(long oldVersionId,long newVersionId){
  var source=db.query("SELECT position,storage_key,mime,size_bytes,width,height,sha256 FROM rs_question_asset WHERE question_version_id=? ORDER BY position",(r,n)->new StoredAsset(r.getInt(1),r.getString(2),r.getString(3),r.getLong(4),r.getInt(5),r.getInt(6),r.getString(7)),oldVersionId);if(source.isEmpty())return;var created=new java.util.ArrayList<Path>();
  try{Files.createDirectories(directory);for(StoredAsset asset:source){String extension=asset.mime().equals("image/png")?".png":".jpg";String key=java.util.UUID.randomUUID().toString().replace("-","")+extension;Path from=path(asset.storageKey()),to=path(key);Files.copy(from,to,StandardCopyOption.COPY_ATTRIBUTES);created.add(to);db.update("INSERT INTO rs_question_asset(question_version_id,position,storage_key,mime,size_bytes,width,height,sha256) VALUES (?,?,?,?,?,?,?,?)",newVersionId,asset.position(),key,asset.mime(),asset.sizeBytes(),asset.width(),asset.height(),asset.sha256());}}
  catch(IOException|RuntimeException e){created.forEach(QuestionAssetService::deleteQuietly);if(e instanceof BusinessException business)throw business;throw new BusinessException(HttpStatus.CONFLICT,"STATE_CONFLICT","Question image could not be copied");}
  if(TransactionSynchronizationManager.isSynchronizationActive())TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){@Override public void afterCompletion(int status){if(status!=STATUS_COMMITTED)created.forEach(QuestionAssetService::deleteQuietly);}});
 }
 @Transactional public void deleteHistorical(long id){
  var rows=db.query("SELECT a.storage_key,(q.current_version_id=a.question_version_id) AS current_version FROM rs_question_asset a JOIN rs_question_version v ON v.id=a.question_version_id JOIN rs_question q ON q.id=v.question_id WHERE a.id=?",(r,n)->new DeleteTarget(r.getString(1),r.getBoolean(2)),id);if(rows.isEmpty())throw new BusinessException(HttpStatus.NOT_FOUND,"NOT_FOUND","Image not found");DeleteTarget target=rows.get(0);if(target.currentVersion())throw new BusinessException(HttpStatus.CONFLICT,"STATE_CONFLICT","Current question version images cannot be deleted");Path file=path(target.storageKey());if(!Files.exists(file))throw new BusinessException(HttpStatus.CONFLICT,"STATE_CONFLICT","Question image file is missing");db.update("DELETE FROM rs_question_asset WHERE id=?",id);try{Files.delete(file);}catch(IOException e){throw new BusinessException(HttpStatus.CONFLICT,"STATE_CONFLICT","Question image file could not be deleted");}
 }
 private static Decoded decode(byte[] bytes){try(ImageInputStream in=ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))){var readers=ImageIO.getImageReaders(in);if(!readers.hasNext())invalid("File is not a decodable PNG or JPEG image");ImageReader reader=readers.next();try{reader.setInput(in,true,true);String format=reader.getFormatName().toLowerCase();if(!format.equals("png")&&!format.equals("jpeg")&&!format.equals("jpg"))invalid("Only PNG and JPEG are allowed");BufferedImage image=reader.read(0);if(image==null||image.getWidth()<1||image.getHeight()<1)invalid("Image cannot be decoded");return new Decoded(format.equals("png")?"image/png":"image/jpeg",format.equals("png")?".png":".jpg",image.getWidth(),image.getHeight());}finally{reader.dispose();}}catch(IOException e){invalid("Image cannot be decoded");return null;}}
 private static String sha256(byte[] bytes){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
 private Path path(String key){Path value=directory.resolve(key).normalize();if(!value.getParent().equals(directory))throw new BusinessException(HttpStatus.NOT_FOUND,"NOT_FOUND","Image not found");return value;}
 private static void deleteQuietly(Path path){try{Files.deleteIfExists(path);}catch(IOException ignored){}}
 private static void invalid(String message){throw new BusinessException(HttpStatus.BAD_REQUEST,"UPLOAD_INVALID",message);}
 record Decoded(String mime,String extension,int width,int height){}
 record StoredAsset(int position,String storageKey,String mime,long sizeBytes,int width,int height,String sha256){}
 record DeleteTarget(String storageKey,boolean currentVersion){}
 public record AssetData(byte[] bytes,MediaType mediaType){}
}
