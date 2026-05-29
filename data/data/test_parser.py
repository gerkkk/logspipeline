import re

line = "23924172 ACC_45615 LAW_333323 ACC_45616"
print(f"Line: {line}")

parts = line.split(maxsplit=2)
print(f"Parts: {parts}")

if len(parts) >= 2:
    command = parts[0]
    second = parts[1]
    is_number = command.replace("-", "").isdigit()
    print(f"Command: {command}, is_number: {is_number}")
    
    if is_number:
        print("This is NUMERIC_COMMAND")
        print(f"Args: {{'data': '{line}'}}")
        
        # Извлекаем все ID документов
        pattern = r'[A-Z]+_\d+'
        docs = re.findall(pattern, line)
        print(f"Documents found: {docs}")
        print(f"Contains ACC_45616: {'ACC_45616' in docs}")
