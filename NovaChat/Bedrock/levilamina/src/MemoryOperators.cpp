// LeviLamina refuses any mod that does not export `ll_memory_operator_overrided`:
// NativeModManager::load() checks for that symbol right after dlopen and aborts
// with "isn't using the unified memory allocation operator". The symbol is only
// emitted when LL_MEMORY_OPERATORS is defined before including the header, and
// the header must be included in exactly one TU. This is that TU.
#define LL_MEMORY_OPERATORS

#include "ll/api/memory/MemoryOperators.h" // IWYU pragma: keep
