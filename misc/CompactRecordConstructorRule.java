public record Example(String value) {

    public Example {
        // `value` is safe here: it is the constructor parameter.
        validate(value);

        // `this.value` is not safe here in a compact constructor:
        // the record field is assigned only after this block completes.
    }

}