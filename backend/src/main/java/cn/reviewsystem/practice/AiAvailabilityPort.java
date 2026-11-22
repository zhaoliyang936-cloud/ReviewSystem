package cn.reviewsystem.practice;

public interface AiAvailabilityPort {
    record AiStatus(boolean available, String reasonCode) {
        public static AiStatus ok() {
            return new AiStatus(true, null);
        }

        public static AiStatus no(String reasonCode) {
            return new AiStatus(false, reasonCode);
        }
    }

    AiStatus status(long userId);

    /** 保留旧的布尔入口，实现类不必重写。 */
    default boolean available(long userId) {
        return status(userId).available();
    }
}
