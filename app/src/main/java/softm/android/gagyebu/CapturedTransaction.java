package softm.android.gagyebu;

public final class CapturedTransaction {
    public final String captureId;
    public final String sourceType;
    public final String sourcePackage;
    public final String sender;
    public final String title;
    public final String body;
    public final long receivedAt;
    public final String kind;
    public final long amount;
    public final String date;
    public final String merchant;
    public final String accountHint;
    public final String accountType;

    public CapturedTransaction(
        String captureId,
        String sourceType,
        String sourcePackage,
        String sender,
        String title,
        String body,
        long receivedAt,
        String kind,
        long amount,
        String date,
        String merchant,
        String accountHint,
        String accountType
    ) {
        this.captureId = captureId;
        this.sourceType = sourceType;
        this.sourcePackage = sourcePackage;
        this.sender = sender;
        this.title = title;
        this.body = body;
        this.receivedAt = receivedAt;
        this.kind = kind;
        this.amount = amount;
        this.date = date;
        this.merchant = merchant;
        this.accountHint = accountHint;
        this.accountType = accountType;
    }
}

