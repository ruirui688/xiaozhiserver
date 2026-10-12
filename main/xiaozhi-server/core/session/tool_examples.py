"""Select complete demonstration turns for the tools in this request."""


def select_tool_examples(messages, available_tools):
    if available_tools is None:
        return messages
    allowed = {item.get("function", {}).get("name") for item in available_tools}
    groups = []
    for message in messages:
        if message.role == "user" or not groups:
            groups.append([])
        groups[-1].append(message)
    selected = []
    for group in groups:
        names = {
            call.get("function", {}).get("name")
            for message in group for call in (message.tool_calls or [])
        }
        if names <= allowed:
            selected.extend(group)
    return selected
