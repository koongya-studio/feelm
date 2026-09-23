"""Non-sensitive, machine-readable failures at the GBT boundary."""


class GbtError(ValueError):
    def __init__(self, code: str, **details):
        self.code = code
        self.details = details
        super().__init__(code)
